package com.kratisai.controlplane.service;

import com.kratisai.controlplane.service.ProcessExecutor.ProcessResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves how the control plane reaches a sandbox-local preview port.
 *
 * <p>The runner container lives on {@code kratis-net-<envId>}, a bridge network the control plane is
 * not a member of. Two topologies are supported:
 *
 * <ul>
 *   <li>Control plane on the host (dev, {@code ./mvnw spring-boot:run}): the host already routes to
 *       the sandbox bridge subnet, so the runner's sandbox IP is dialed directly.
 *   <li>Control plane in a container (deploy): the control plane idempotently joins
 *       {@code kratis-net-<envId>} with {@code docker network connect}, then dials the runner by its
 *       sandbox-network container name.
 * </ul>
 *
 * <p>Both cases are driven through the docker CLI on the control plane's daemon. Discovery is cached
 * per environment for the lifetime of the process; the sandbox IP only changes on sandbox respawn.
 */
@Service
public class SandboxPreviewReachability {

    private static final Logger logger = LoggerFactory.getLogger(SandboxPreviewReachability.class);

    private static final String NETWORK_PREFIX = "kratis-net-";
    private static final String RUNNER_PREFIX = "kratis-sandbox-";

    private final ProcessExecutor processExecutor;

    /** Package-private so tests can pin the topology; production defaults to /.dockerenv presence. */
    boolean runningInContainer;

    /** Networks this control-plane container has already joined; the join is idempotent but not free. */
    private final Set<String> joinedNetworks = ConcurrentHashMap.newKeySet();

    public SandboxPreviewReachability(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
        this.runningInContainer = Files.exists(Path.of("/.dockerenv"));
    }

    /**
     * The upstream base URL the preview proxy dials, e.g. {@code http://10.210.1.3:3000}.
     *
     * @param environmentId the sandbox environment id
     * @param port the sandbox-local port the application listens on
     * @return the internal upstream URL, never null
     * @throws PreviewUnreachableException when the runner address cannot be resolved or the control
     *     plane cannot join the sandbox network
     */
    public String resolveUpstream(UUID environmentId, int port) {
        String envId = environmentId.toString();
        String network = NETWORK_PREFIX + envId;
        String runner = RUNNER_PREFIX + envId;

        String runnerAddress = runnerAddressOnNetwork(network, runner);
        if (runnerAddress == null) {
            throw new PreviewUnreachableException("Runner container " + runner + " has no address on " + network);
        }

        if (runningInContainer && !ensureJoinedNetwork(network)) {
            throw new PreviewUnreachableException("Control plane could not join sandbox network " + network);
        }
        return "http://" + runnerAddress + ":" + port;
    }

    /** Read the runner's IPv4 address on the sandbox network via docker inspect. */
    private String runnerAddressOnNetwork(String network, String runner) {
        try {
            ProcessResult result = processExecutor.execute(
                    List.of(
                            "docker",
                            "inspect",
                            "--format",
                            "{{(index .NetworkSettings.Networks \"" + network + "\").IPAddress}}",
                            runner),
                    null,
                    null);
            if (result.exitCode() != 0) {
                logger.warn("Could not inspect runner {} on {}: exit {}", runner, network, result.exitCode());
                return null;
            }
            String address = new String(result.output()).trim();
            return address.isEmpty() ? null : address;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted resolving runner address for {}", runner, e);
            return null;
        }
    }

    /** Idempotently attach this control-plane container to the sandbox network. */
    private boolean ensureJoinedNetwork(String network) {
        if (joinedNetworks.contains(network)) {
            return true;
        }
        String self = selfContainerId();
        if (self == null) {
            return false;
        }
        try {
            ProcessResult result =
                    processExecutor.execute(List.of("docker", "network", "connect", network, self), null, null);
            if (result.exitCode() == 0) {
                joinedNetworks.add(network);
                logger.info("Control plane joined sandbox network {}", network);
                return true;
            }
            String message = new String(result.output()).trim();
            // "already connected" is success; anything else is a real failure.
            boolean already = message.toLowerCase().contains("already") || message.contains("existing");
            if (already) {
                joinedNetworks.add(network);
                return true;
            }
            logger.warn("docker network connect {} {} failed (exit {}): {}", network, self, result.exitCode(), message);
            return false;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted joining sandbox network {}", network, e);
            return false;
        }
    }

    private String selfContainerId() {
        try {
            ProcessResult result = processExecutor.execute(List.of("hostname"), null, null);
            if (result.exitCode() == 0) {
                String hostname = new String(result.output()).trim();
                if (!hostname.isEmpty()) {
                    return hostname;
                }
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Thrown when the control plane cannot establish a route to the sandbox preview port. */
    public static class PreviewUnreachableException extends RuntimeException {
        public PreviewUnreachableException(String message) {
            super(message);
        }
    }
}
