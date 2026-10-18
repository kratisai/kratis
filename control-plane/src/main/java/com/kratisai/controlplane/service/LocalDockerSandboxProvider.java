package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LocalDockerSandboxProvider implements SandboxProvider {
    private static final Logger logger = LoggerFactory.getLogger(LocalDockerSandboxProvider.class);
    public static final String SIDECAR_LOG_LOC = "/kratis/logs/sidecar.log";

    private static final List<String> REQUIRED_SANDBOX_DIRECTORIES = List.of("/kratis/workspace", "/kratis/logs");

    private static final Path APPARMOR_USERNS_RESTRICTION_SYSCTL =
            Path.of("/proc/sys/kernel/apparmor_restrict_unprivileged_userns");

    /**
     * Capabilities re-granted so the sandbox user can install packages through
     * sudo (apt-get/dpkg need to write system paths, chown/chmod files, create
     * users, and signal maintainer scripts). Everything else - in particular
     * CAP_SYS_ADMIN (mounting the host docker socket), CAP_SYS_PTRACE,
     * CAP_NET_ADMIN and CAP_NET_RAW - stays dropped.
     */
    private static final List<String> PACKAGE_MANAGEMENT_CAPABILITIES =
            List.of("CHOWN", "DAC_OVERRIDE", "FOWNER", "FSETID", "KILL", "SETGID", "SETUID", "AUDIT_WRITE");

    private final ProcessExecutor processExecutor;
    private final String instanceId;
    private final String registryMirror;
    private final String runnerImage;
    private final boolean debug;
    private String connectUrl;

    private final Map<String, Instant> danglingNetworkFirstSeen = new ConcurrentHashMap<>();

    public LocalDockerSandboxProvider(
            ProcessExecutor processExecutor,
            SandboxProperties sandboxProperties,
            InstanceProperties instanceProperties) {
        this.processExecutor = processExecutor;
        this.instanceId = instanceProperties.getId();
        this.registryMirror = sandboxProperties.getDocker().getRegistryMirror();
        this.runnerImage = sandboxProperties.getDocker().getRunnerImage();
        this.debug = sandboxProperties.getDocker().isDebug();
        this.connectUrl = sandboxProperties.getDocker().getConnectUrl();
    }

    public void setConnectUrl(String connectUrl) {
        this.connectUrl = connectUrl;
    }

    @Override
    public ExecutionProviderType getProviderType() {
        return ExecutionProviderType.DOCKER;
    }

    private String resolveHostConnectorPath() {
        String envPath = System.getenv("KRATIS_CONNECTOR_PATH");
        if (envPath != null && !envPath.isBlank()) {
            return envPath;
        }
        String sysProp = System.getProperty("kratis.connector.path");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }
        for (String candidate : List.of("../sidecar/kratis-connector", "sidecar/kratis-connector")) {
            File f = new File(candidate);
            if (f.exists()) {
                try {
                    return f.getCanonicalPath();
                } catch (IOException e) {
                    return f.getAbsolutePath();
                }
            }
        }
        return "/usr/local/bin/kratis-connector";
    }

    private static String requireEnvId(ExecutionEnvironment environment) {
        if (environment == null || environment.getId() == null) {
            throw new IllegalArgumentException("Environment id is required to address sandbox resources");
        }
        return environment.getId().toString();
    }

    private static String runnerName(String envId) {
        return "kratis-sandbox-" + envId;
    }

    private static String dindName(String envId) {
        return "kratis-dind-" + envId;
    }

    private static String networkName(String envId) {
        return "kratis-net-" + envId;
    }

    // The committed image tag subsumes any prior version, so the mapping envId -> image is
    // deterministic and survives control-plane restarts without provider-side in-memory state.
    private static String snapshotRef(String envId) {
        return "kratis-snapshot-" + envId + ":latest";
    }

    @Override
    public void spawnSandbox(ExecutionEnvironment environment) {
        String envId = requireEnvId(environment);
        logger.info("Spawning sandbox environment {}", envId);

        try {
            createSandboxNetwork(envId, networkName(envId));
            spawnDindSibling(envId, networkName(envId), dindName(envId));
            spawnRunnerContainer(environment, envId, networkName(envId), dindName(envId), runnerName(envId), null);
        } catch (RuntimeException e) {
            terminateSandboxResources(envId);
            throw e;
        }
    }

    @Override
    public boolean isSandboxRunning(UUID envId) {
        if (envId == null) {
            return false;
        }
        String runnerRef = resolveRunnerRef(envId.toString());
        if (runnerRef == null) {
            return false;
        }
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of("docker", "inspect", "--format", "{{.State.Running}}", runnerRef), null, null);
            return result.exitCode() == 0 && "true".equals(new String(result.output()).trim());
        } catch (IOException | InterruptedException e) {
            // The runtime state is unobservable; report running so callers keep the suspend path.
            logger.warn(
                    "Failed to inspect running state of container {} for environment {}: {}",
                    runnerRef,
                    envId,
                    e.getMessage());
            return true;
        }
    }

    @Override
    public void suspend(UUID envId) {
        if (envId == null) {
            throw new IllegalArgumentException("Environment id is required to suspend a sandbox");
        }
        logger.info("Suspending sandbox environment {}", envId);
        String envKey = envId.toString();
        String runnerRef = resolveRunnerRef(envKey);
        if (runnerRef == null) {
            throw new IllegalStateException("No runner container found for environment " + envId);
        }
        commitSnapshot(envKey, runnerRef);
        terminateSandboxResources(envKey);
    }

    @Override
    public void resume(ExecutionEnvironment environment) {
        String envId = requireEnvId(environment);
        String snapshot = snapshotRef(envId);
        if (imageIdOf(snapshot) == null) {
            throw new IllegalStateException("No suspended snapshot exists for environment " + envId);
        }
        logger.info("Resuming sandbox environment {} from snapshot {}", envId, snapshot);

        try {
            createSandboxNetwork(envId, networkName(envId));
            spawnDindSibling(envId, networkName(envId), dindName(envId));
            spawnRunnerContainer(environment, envId, networkName(envId), dindName(envId), runnerName(envId), snapshot);
        } catch (RuntimeException e) {
            terminateSandboxResources(envId);
            throw e;
        }
    }

    @Override
    public void terminateSandbox(UUID envId) {
        if (envId == null) {
            return;
        }
        logger.info("Terminating sandbox runtime for environment {}", envId);
        terminateSandboxResources(envId.toString());
    }

    @Override
    public void destroy(UUID envId) {
        if (envId == null) {
            return;
        }
        String envKey = envId.toString();
        try {
            terminateSandboxResources(envKey);
        } catch (RuntimeException e) {
            logger.warn("Failed to terminate sandbox resources for environment {}: {}", envId, e.getMessage());
        }
        try {
            removeSnapshot(envKey);
        } catch (Exception e) {
            logger.warn("Failed to remove suspend snapshot for environment {}: {}", envId, e.getMessage());
        }
    }

    @Override
    public void captureSidecarLogs(UUID envId) {
        if (envId == null) {
            return;
        }
        String runnerRef = resolveRunnerRef(envId.toString());
        if (runnerRef == null) {
            return;
        }
        extractAndLogSidecarLogs(runnerRef);
    }

    @Override
    public Set<UUID> listActiveEnvIds() {
        return listEnvIdLabels(List.of(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=kratis.instance.id=" + instanceId,
                "--filter",
                "label=kratis.role=runner",
                "--format",
                "{{.Label \"kratis.sandbox.id\"}}"));
    }

    @Override
    public Set<UUID> listSnapshotEnvIds() {
        // Scoped to this instance's resume snapshots only: the tag prefix excludes every other image
        // and the instance label excludes snapshots owned by another control plane.
        return listEnvIdLabels(List.of(
                "docker",
                "images",
                "--filter",
                "reference=kratis-snapshot-*",
                "--filter",
                "label=kratis.instance.id=" + instanceId,
                "--filter",
                "label=kratis.role=runner",
                "--format",
                "{{.Label \"kratis.sandbox.id\"}}"));
    }

    private Set<UUID> listEnvIdLabels(List<String> command) {
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
            if (result.exitCode() != 0) {
                logger.warn("Docker command failed with exit code: {}", result.exitCode());
                return Set.of();
            }
            String output = new String(result.output()).trim();
            if (output.isEmpty()) {
                return Set.of();
            }
            Set<UUID> envIds = new LinkedHashSet<>();
            for (String line : output.split("\\s+")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    envIds.add(UUID.fromString(line));
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping sandbox resource with unparseable kratis.sandbox.id label: {}", line);
                }
            }
            return envIds;
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to list sandbox environments from Docker host: {}", e.getMessage());
            return Set.of();
        }
    }

    @Override
    public void pruneOrphanNetworks(Duration gracePeriod) {
        try {
            Instant now = Instant.now();
            // List networks created by this instance with role=network
            List<String> listCmd = List.of(
                    "docker",
                    "network",
                    "ls",
                    "--filter",
                    "label=kratis.instance.id=" + instanceId,
                    "--filter",
                    "label=kratis.role=network",
                    "--format",
                    "{{.Name}}");
            ProcessExecutor.ProcessResult listResult = processExecutor.execute(listCmd, null, null);
            if (listResult.exitCode() != 0) {
                return;
            }
            String output = new String(listResult.output()).trim();
            Set<String> listedNetworks = new HashSet<>();
            if (!output.isEmpty()) {
                String[] networkNames = output.split("\\s+");
                for (String netName : networkNames) {
                    if (netName.isBlank()) {
                        continue;
                    }
                    listedNetworks.add(netName);
                    // Check if any containers are attached to this network
                    List<String> inspectCmd =
                            List.of("docker", "network", "inspect", "--format", "{{len .Containers}}", netName);
                    ProcessExecutor.ProcessResult inspectResult = processExecutor.execute(inspectCmd, null, null);
                    if (inspectResult.exitCode() != 0) {
                        continue;
                    }
                    String countStr = new String(inspectResult.output()).trim();
                    if ("0".equals(countStr)) {
                        Instant firstSeen = danglingNetworkFirstSeen.computeIfAbsent(netName, k -> now);
                        long elapsed = Duration.between(firstSeen, now).toSeconds();
                        if (elapsed >= gracePeriod.toSeconds()) {
                            logger.info("Pruning dangling kratis network with 0 attached containers: {}", netName);
                            if (removeNetwork(netName)) {
                                danglingNetworkFirstSeen.remove(netName);
                            } else {
                                logger.warn("Failed to prune dangling kratis network {}", netName);
                            }
                        } else {
                            logger.debug(
                                    "Dangling kratis network {} tracked ({}s elapsed / {}s grace)",
                                    netName,
                                    elapsed,
                                    gracePeriod.toSeconds());
                        }
                    } else {
                        // Network has attached containers; clear any dangling tracking
                        danglingNetworkFirstSeen.remove(netName);
                    }
                }
            }
            // Drop tracking for networks that no longer exist
            danglingNetworkFirstSeen.keySet().removeIf(name -> !listedNetworks.contains(name));
        } catch (Exception e) {
            logger.debug("Failed during orphan network pruning: {}", e.getMessage());
        }
    }

    private void removeSnapshot(String envId) {
        String snapshot = snapshotRef(envId);
        logger.info("Removing sandbox snapshot image {}", snapshot);
        try {
            ProcessExecutor.ProcessResult result =
                    processExecutor.execute(List.of("docker", "rmi", "-f", snapshot), null, null);
            if (result.exitCode() != 0) {
                logger.debug("Snapshot image {} already absent", snapshot);
            }
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to remove sandbox snapshot image {}: {}", snapshot, e.getMessage());
        }
    }

    private void commitSnapshot(String envId, String runnerRef) {
        String snapshot = snapshotRef(envId);
        String previousImageId = imageIdOf(snapshot);
        executeCommand("Committing sandbox snapshot", List.of("docker", "commit", runnerRef, snapshot));
        String committedImageId = imageIdOf(snapshot);
        if (previousImageId != null && !previousImageId.equals(committedImageId)) {
            try {
                processExecutor.execute(List.of("docker", "rmi", "-f", previousImageId), null, null);
            } catch (IOException | InterruptedException e) {
                logger.warn("Failed to remove superseded snapshot image {}: {}", previousImageId, e.getMessage());
            }
        }
    }

    private String imageIdOf(String imageRef) {
        try {
            ProcessExecutor.ProcessResult result =
                    processExecutor.execute(List.of("docker", "inspect", "--format", "{{.Id}}", imageRef), null, null);
            if (result.exitCode() != 0) {
                return null;
            }
            String output = new String(result.output()).trim();
            return output.isEmpty() ? null : output;
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to inspect image {}: {}", imageRef, e.getMessage());
            return null;
        }
    }

    private void createSandboxNetwork(String envId, String networkName) {
        List<String> command = List.of(
                "docker",
                "network",
                "create",
                "--label",
                "kratis.instance.id=" + instanceId,
                "--label",
                "kratis.sandbox.id=" + envId,
                "--label",
                "kratis.role=network",
                networkName);
        executeCommand("Creating docker network", command);
    }

    private void spawnDindSibling(String envId, String networkName, String dindName) {
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "run",
                "-d",
                "--name",
                dindName,
                "--network",
                networkName,
                "--security-opt",
                "seccomp=unconfined",
                // Docker's masked paths leave non-empty child mounts under /proc, which the kernel
                // rejects a nested procfs mount over (fs_fully_visible), so no inner container
                // could start. Removing the masks grants no capability, device, or socket.
                "--security-opt",
                "systempaths=unconfined",
                //  /dev/net/tun needed for rootlesskit's slirp4netns network driver
                "--device",
                "/dev/net/tun"));
        if (apparmorRestrictsUnprivilegedUserns()) {
            // enable unprivileged userns_create needed for rootlesskit.
            command.addAll(List.of("--security-opt", "apparmor=rootlesskit"));
        }
        command.addAll(List.of(
                "--add-host",
                "host.docker.internal:host-gateway",
                "-e",
                "DOCKER_TLS_CERTDIR=",
                "--label",
                "kratis.instance.id=" + instanceId,
                "--label",
                "kratis.sandbox.id=" + envId,
                "--label",
                "kratis.role=dind",
                "docker:dind-rootless",
                "dockerd-entrypoint.sh"));
        // dockerd maps `host-gateway` to its own bridge by default, which is this sibling's daemon
        // rather than the machine running the control plane. Point it at this process instead so
        // every container inside the sibling reaches the control plane via host.docker.internal.
        String gateway = dockerNetworkGateway(networkName);
        if (gateway != null) {
            command.add("--host-gateway-ip=" + resolveContainerReachableHost(gateway));
        } else {
            logger.warn(
                    "Could not resolve the gateway of {} (kratis.sandbox.*); containers inside the sibling"
                            + " will not be able to reach this control plane via host.docker.internal",
                    networkName);
        }
        if (registryMirror != null && !registryMirror.isBlank()) {
            warnIfRegistryMirrorUnreachable(registryMirror);
            command.add("--registry-mirror=" + registryMirror);
        }
        executeCommand("Spawning dind container", command);
    }

    /**
     * Address that containers reach this control plane on. The docker bridge gateway does that
     * when this JVM runs on the host; inside a container the gateway leads to the docker host
     * instead, so use our own address.
     */
    public static String resolveContainerReachableHost(String dockerGatewayIp) {
        if (!Files.exists(Path.of("/.dockerenv"))) {
            return dockerGatewayIp;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return dockerGatewayIp;
        }
    }

    private String dockerNetworkGateway(String networkName) {
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of(
                            "docker",
                            "network",
                            "inspect",
                            "--format",
                            "{{(index .IPAM.Config 0).Gateway}}",
                            networkName),
                    null,
                    null);
            if (result.exitCode() != 0) {
                logger.warn("Could not read the gateway of docker network {}", networkName);
                return null;
            }
            String output = new String(result.output()).trim();
            return output.isBlank() ? null : output;
        } catch (IOException | InterruptedException | RuntimeException e) {
            logger.warn("Failed to read the gateway of docker network {}", networkName, e);
            return null;
        }
    }

    private void warnIfRegistryMirrorUnreachable(String registryMirror) {
        try {
            URI uri = URI.create(registryMirror);
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            if (!isReachable(uri.getHost(), port)) {
                logger.warn(
                        "Registry mirror {} is unreachable; image pulls will bypass the cache"
                                + " (check kratis.sandbox.docker.registry-mirror)",
                        registryMirror);
            }
        } catch (RuntimeException e) {
            logger.debug("Could not probe registry mirror {}", registryMirror, e);
        }
    }

    static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Check whether userns is allowed for unprivileged users
    static boolean apparmorRestrictsUnprivilegedUserns() {
        return isSysctlEnabled(APPARMOR_USERNS_RESTRICTION_SYSCTL);
    }

    static boolean isSysctlEnabled(Path sysctl) {
        try {
            return Files.isReadable(sysctl)
                    && "1".equals(Files.readString(sysctl).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private void spawnRunnerContainer(
            ExecutionEnvironment environment,
            String envId,
            String networkName,
            String dindName,
            String runnerName,
            String imageOverride) {
        String image = imageOverride;
        if (image == null) {
            image = runnerImage;
            if (environment.getProvider() != null && environment.getProvider().getDockerImage() != null) {
                image = environment.getProvider().getDockerImage();
            }
        }

        String gateway = dockerNetworkGateway(networkName);
        String hostInternal = gateway != null ? resolveContainerReachableHost(gateway) : "host-gateway";

        List<String> command = new ArrayList<>(List.of(
                "docker",
                "run",
                "-d",
                "--name",
                runnerName,
                "--network",
                networkName,
                "--add-host",
                "host.docker.internal:" + hostInternal,
                "-e",
                "DOCKER_HOST=tcp://" + dindName + ":2375",
                "-e",
                "TESTCONTAINERS_HOST_OVERRIDE=" + dindName,
                "-e",
                "TESTCONTAINERS_RYUK_DISABLED=true",
                // Ryuk cannot reach the sibling daemon, so nothing reaps testcontainers. Reuse
                // keeps each `mvnw test` from leaving another Postgres + LiteLLM pair behind.
                "-e",
                "TESTCONTAINERS_REUSE_ENABLE=true",
                "--label",
                "kratis.instance.id=" + instanceId,
                "--label",
                "kratis.sandbox.id=" + envId,
                "--label",
                "kratis.role=runner",
                "--cap-drop=ALL"));
        PACKAGE_MANAGEMENT_CAPABILITIES.forEach(capability -> command.add("--cap-add=" + capability));
        command.addAll(List.of("--user=1000", "--entrypoint", "", image, "sleep", "infinity"));

        executeCommand("Spawning runner container", command);
    }

    private void executeCommand(String actionDescription, List<String> command) {
        logger.info("{}: {}", actionDescription, String.join(" ", command));
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
            if (result.exitCode() != 0) {
                String errorMsg = new String(result.output()).trim();
                logger.error("{} failed. Exit code: {}, Output: {}", actionDescription, result.exitCode(), errorMsg);
                throw new RuntimeException("Failed to spawn docker sandbox: " + errorMsg);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("{} failed with exception", actionDescription, e);
            throw new RuntimeException("Failed to spawn docker sandbox: " + e.getMessage(), e);
        }
    }

    private void verifySandboxDirectoryWritable(String runnerRef, String directory)
            throws IOException, InterruptedException {
        List<String> command = List.of(
                "docker", "exec", runnerRef, "sh", "-c", "test -d '" + directory + "' && test -w '" + directory + "'");
        ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
        if (result.exitCode() != 0) {
            String errorDetail = String.format(
                    "Sandbox image contract violation in container %s: directory %s does not exist or is not "
                            + "writable by the sandbox's default (non-root) user. Sandbox images must pre-create "
                            + "and chown this directory to that user at IMAGE BUILD TIME (see "
                            + "build/Dockerfile.runner-base for the reference implementation); ownership is never "
                            + "fixed up at container runtime.",
                    runnerRef, directory);
            logger.error(errorDetail);
            throw new RuntimeException(errorDetail);
        }
    }

    @Override
    public void initializeWorkspace(UUID envId, String token) {
        String envKey = envId.toString();
        String runnerRef = resolveRunnerRef(envKey);
        if (runnerRef == null) {
            throw new RuntimeException("No runner container found for environment " + envId);
        }
        logger.info("Initializing workspace and installing sidecar for environment {}", envId);

        // Inject the kratis-connector binary via docker cp
        try {
            for (String directory : REQUIRED_SANDBOX_DIRECTORIES) {
                verifySandboxDirectoryWritable(runnerRef, directory);
            }

            String hostBinaryPath = resolveHostConnectorPath();
            File hostBinaryFile = new File(hostBinaryPath);
            if (hostBinaryFile.exists() && !hostBinaryFile.canExecute()) {
                if (!hostBinaryFile.setExecutable(true, false)) {
                    logger.warn(
                            "Failed to mark connector binary {} executable before injection; docker cp may not "
                                    + "preserve executable bits",
                            hostBinaryPath);
                }
            }
            logger.info("Injecting connector from host {} to environment {}", hostBinaryPath, envId);

            ProcessExecutor.ProcessResult cpResult = processExecutor.execute(
                    List.of("docker", "cp", hostBinaryPath, runnerRef + ":/usr/local/bin/kratis-connector"),
                    null,
                    null);
            if (cpResult.exitCode() != 0) {
                throw new RuntimeException("docker cp failed: " + new String(cpResult.output()));
            }

            List<String> execCommand = new ArrayList<>(List.of(
                    "docker",
                    "exec",
                    "-d",
                    "-w",
                    "/kratis/workspace",
                    runnerRef,
                    "/usr/local/bin/kratis-connector",
                    "--mode=sidecar",
                    "--server-url",
                    connectUrl,
                    "--token",
                    token,
                    "--log-file",
                    SIDECAR_LOG_LOC));
            if (debug) {
                execCommand.add("--debug");
            }

            logger.info("Starting connector in environment {} with command: {}", envId, String.join(" ", execCommand));
            ProcessExecutor.ProcessResult execResult = processExecutor.execute(execCommand, null, null);
            if (execResult.exitCode() != 0) {
                throw new RuntimeException("docker exec to start connector failed: " + new String(execResult.output()));
            }

        } catch (RuntimeException | InterruptedException | IOException e) {
            logger.error("Failed to inject/run connector for environment {}", envId, e);
            captureSidecarLogs(envId);
            terminateSandboxResources(envKey);
            throw new RuntimeException("Failed to initialize connector in environment: " + e.getMessage(), e);
        }
    }

    private String resolveRunnerRef(String envId) {
        String byLabel = findRunnerContainerByLabel(envId);
        if (byLabel != null) {
            return byLabel;
        }
        String name = runnerName(envId);
        return containerExists(name) ? name : null;
    }

    private boolean containerExists(String containerRef) {
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of("docker", "inspect", "--format", "{{.Id}}", containerRef), null, null);
            return result.exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            logger.debug("Failed to inspect container {}: {}", containerRef, e.getMessage());
            return false;
        }
    }

    private String findRunnerContainerByLabel(String envId) {
        List<String> command = List.of(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=kratis.instance.id=" + instanceId,
                "--filter",
                "label=kratis.sandbox.id=" + envId,
                "--filter",
                "label=kratis.role=runner",
                "--no-trunc",
                "-q");
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
            if (result.exitCode() != 0) {
                return null;
            }
            String output = new String(result.output()).trim();
            if (output.isEmpty()) {
                return null;
            }
            return output.split("\\s+")[0];
        } catch (IOException | InterruptedException e) {
            logger.debug("Failed to resolve runner container by label for environment {}: {}", envId, e.getMessage());
            return null;
        }
    }

    private void terminateSandboxResources(String envId) {
        // -v matters: docker:dind-rootless declares two VOLUMEs holding its image store, so each
        // sibling leaks gigabytes without it.
        String runnerRef = findRunnerContainerByLabel(envId);
        String runnerName = runnerName(envId);
        if (runnerRef != null) {
            try {
                processExecutor.execute(List.of("docker", "rm", "-f", "-v", runnerRef), null, null);
            } catch (IOException | InterruptedException e) {
                logger.error("Failed to terminate runner container: {}", runnerRef, e);
            }
        }
        if (!runnerName.equals(runnerRef)) {
            try {
                processExecutor.execute(List.of("docker", "rm", "-f", "-v", runnerName), null, null);
            } catch (Exception e) {
                logger.debug("Runner container {} already removed or not found", runnerName);
            }
        }

        // Remove DinD sibling container
        String dindName = dindName(envId);
        try {
            processExecutor.execute(List.of("docker", "rm", "-f", "-v", dindName), null, null);
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to terminate dind container: {}", dindName, e);
        }

        // Remove network. Disconnect first: a stopped but still-attached container blocks
        // `network rm` and would otherwise leak one /24 from the daemon pool per sandbox.
        String networkName = networkName(envId);
        forceDisconnectAll(networkName);
        try {
            ProcessExecutor.ProcessResult rmResult =
                    processExecutor.execute(List.of("docker", "network", "rm", networkName), null, null);
            if (rmResult.exitCode() != 0) {
                logger.error(
                        "Failed to remove docker network {}: {}", networkName, new String(rmResult.output()).trim());
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to remove docker network: {}", networkName, e);
        }
    }

    private Set<String> listAttachedContainers(String networkName) {
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of(
                            "docker",
                            "network",
                            "inspect",
                            "--format",
                            "{{range $k, $v := .Containers}}{{$v.Name}} {{end}}",
                            networkName),
                    null,
                    null);
            if (result.exitCode() != 0) {
                return Set.of();
            }
            String output = new String(result.output()).trim();
            if (output.isEmpty()) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            for (String name : output.split("\\s+")) {
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException | InterruptedException e) {
            logger.debug("Failed to list attached containers for network {}: {}", networkName, e.getMessage());
            return Set.of();
        }
    }

    private void forceDisconnectAll(String networkName) {
        for (String container : listAttachedContainers(networkName)) {
            try {
                processExecutor.execute(
                        List.of("docker", "network", "disconnect", "-f", networkName, container), null, null);
            } catch (IOException | InterruptedException e) {
                logger.debug(
                        "Failed to disconnect container {} from network {}: {}",
                        container,
                        networkName,
                        e.getMessage());
            }
        }
    }

    private boolean removeNetwork(String networkName) {
        forceDisconnectAll(networkName);
        try {
            ProcessExecutor.ProcessResult result =
                    processExecutor.execute(List.of("docker", "network", "rm", networkName), null, null);
            return result.exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            logger.debug("Failed to remove docker network {}: {}", networkName, e.getMessage());
            return false;
        }
    }

    /** Copies the sidecar's log file out of the container so abnormal teardowns leave a trace. */
    private void extractAndLogSidecarLogs(String runnerRef) {
        try {
            Path tempLog = Files.createTempFile("kratis-sidecar-", ".log");
            try {
                ProcessExecutor.ProcessResult cpResult = processExecutor.execute(
                        List.of("docker", "cp", runnerRef + ":" + SIDECAR_LOG_LOC, tempLog.toString()), null, null);
                if (cpResult.exitCode() == 0) {
                    String logs = Files.readString(tempLog);
                    if (!logs.isBlank()) {
                        logger.info("Sidecar logs for container {}:\n{}", runnerRef, logs);
                    } else {
                        logger.info("Sidecar log file is empty for container {}", runnerRef);
                    }
                } else {
                    logger.warn(
                            "Failed to extract sidecar log file from container {} (exit code {}): {}",
                            runnerRef,
                            cpResult.exitCode(),
                            new String(cpResult.output()));
                }
            } finally {
                Files.deleteIfExists(tempLog);
            }
        } catch (Exception e) {
            logger.warn("Failed to extract container logs for {}: {}", runnerRef, e.getMessage());
        }
    }
}
