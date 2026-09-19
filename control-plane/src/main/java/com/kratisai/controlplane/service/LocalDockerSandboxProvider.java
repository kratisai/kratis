package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final String instanceId;
    private final String registryMirror;
    private final String runnerImage;
    private final boolean debug;
    private String serverUrl;

    public LocalDockerSandboxProvider(
            ProcessExecutor processExecutor,
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            @Value("${kratis.instance.id}") String instanceId,
            @Value("${kratis.sandbox.server-url:ws://host.docker.internal:8080/ws/env}") String serverUrl,
            @Value("${kratis.sandbox.registry-mirror:http://host.docker.internal:5001}") String registryMirror,
            @Value("${kratis.sandbox.runner-image:kratis-runner-base:latest}") String runnerImage,
            @Value("${kratis.sandbox.debug:false}") boolean debug) {
        this.processExecutor = processExecutor;
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.instanceId = instanceId;
        this.serverUrl = serverUrl;
        this.registryMirror = registryMirror;
        this.runnerImage = runnerImage;
        this.debug = debug;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @Override
    public ExecutionProviderType getProviderType() {
        return ExecutionProviderType.DOCKER;
    }

    private String resolveHostConnectorPath() {
        String envPath = System.getenv("KRATIS_CONNECTOR_PATH");
        if (envPath != null && !envPath.isEmpty()) {
            return envPath;
        }
        String sysProp = System.getProperty("kratis.connector.path");
        if (sysProp != null && !sysProp.isEmpty()) {
            return sysProp;
        }
        java.io.File[] paths = new java.io.File[] {
            new java.io.File("sidecar/kratis-connector"),
            new java.io.File("../sidecar/kratis-connector"),
            new java.io.File("/usr/local/bin/kratis-connector")
        };
        for (java.io.File file : paths) {
            if (file.exists() && file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        return "/usr/local/bin/kratis-connector";
    }

    @Override
    public String spawnSandbox(ExecutionEnvironment environment, String token) {
        String envId = environment.getId() != null
                ? environment.getId().toString()
                : UUID.randomUUID().toString();
        String networkName = "kratis-net-" + envId;
        String dindName = "kratis-dind-" + envId;
        String runnerName = "kratis-sandbox-" + envId;

        try {
            createSandboxNetwork(envId, networkName);
            spawnDindSibling(envId, networkName, dindName);
            return spawnRunnerContainer(environment, envId, networkName, dindName, runnerName);
        } catch (RuntimeException e) {
            terminateSandboxResources(envId, runnerName);
            throw e;
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
                                + " (check kratis.sandbox.registry-mirror)",
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

    private String spawnRunnerContainer(
            ExecutionEnvironment environment, String envId, String networkName, String dindName, String runnerName) {
        String image = runnerImage;
        if (environment.getProvider() != null && environment.getProvider().getDockerImage() != null) {
            image = environment.getProvider().getDockerImage();
        }

        List<String> command = new ArrayList<>(List.of(
                "docker",
                "run",
                "-d",
                "--name",
                runnerName,
                "--network",
                networkName,
                "--add-host",
                "host.docker.internal:host-gateway",
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

        return executeCommand("Spawning runner container", command);
    }

    private String executeCommand(String actionDescription, List<String> command) {
        logger.info("{}: {}", actionDescription, String.join(" ", command));
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
            if (result.exitCode() != 0) {
                String errorMsg = new String(result.output()).trim();
                logger.error("{} failed. Exit code: {}, Output: {}", actionDescription, result.exitCode(), errorMsg);
                throw new RuntimeException("Failed to spawn docker sandbox: " + errorMsg);
            }
            return new String(result.output()).trim();
        } catch (IOException | InterruptedException e) {
            logger.error("{} failed with exception", actionDescription, e);
            throw new RuntimeException("Failed to spawn docker sandbox: " + e.getMessage(), e);
        }
    }

    private void verifySandboxDirectoryWritable(String containerId, String directory)
            throws IOException, InterruptedException {
        List<String> command = List.of(
                "docker",
                "exec",
                containerId,
                "sh",
                "-c",
                "test -d '" + directory + "' && test -w '" + directory + "'");
        ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
        if (result.exitCode() != 0) {
            String errorDetail = String.format(
                    "Sandbox image contract violation in container %s: directory %s does not exist or is not "
                            + "writable by the sandbox's default (non-root) user. Sandbox images must pre-create "
                            + "and chown this directory to that user at IMAGE BUILD TIME (see "
                            + "build/Dockerfile.runner-base for the reference implementation); ownership is never "
                            + "fixed up at container runtime.",
                    containerId, directory);
            logger.error(errorDetail);
            throw new RuntimeException(errorDetail);
        }
    }

    @Override
    public void initializeWorkspace(String containerId) {
        logger.info("Initializing workspace and installing sidecar for container {}", containerId);

        ExecutionEnvironment environment = executionEnvironmentRepository
                .findByContainerId(containerId)
                .orElseThrow(() -> new RuntimeException("Environment not found for container ID: " + containerId));
        String token = environment.getAuthToken();

        // 2. Inject the kratis-connector binary via docker cp
        try {
            for (String directory : REQUIRED_SANDBOX_DIRECTORIES) {
                verifySandboxDirectoryWritable(containerId, directory);
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
            logger.info(
                    "Injecting connector from host {} to container {} at /usr/local/bin/kratis-connector",
                    hostBinaryPath,
                    containerId);

            ProcessExecutor.ProcessResult cpResult = processExecutor.execute(
                    List.of("docker", "cp", hostBinaryPath, containerId + ":/usr/local/bin/kratis-connector"),
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
                    containerId,
                    "/usr/local/bin/kratis-connector",
                    "--mode=sidecar",
                    "--server-url",
                    serverUrl,
                    "--token",
                    token,
                    "--log-file",
                    SIDECAR_LOG_LOC,
                    "--container-id",
                    containerId));
            if (debug) {
                execCommand.add("--debug");
            }

            logger.info(
                    "Starting connector in container {} with command: {}", containerId, String.join(" ", execCommand));
            ProcessExecutor.ProcessResult execResult = processExecutor.execute(execCommand, null, null);
            if (execResult.exitCode() != 0) {
                throw new RuntimeException("docker exec to start connector failed: " + new String(execResult.output()));
            }

        } catch (RuntimeException | InterruptedException | IOException e) {
            logger.error("Failed to inject/run connector in container {}", containerId, e);
            terminateSandbox(containerId);
            throw new RuntimeException("Failed to initialize connector in container: " + e.getMessage(), e);
        }
    }

    @Override
    public void terminateSandbox(String containerId) {
        logger.info("Terminating docker sandbox container: {}", containerId);
        if (shouldCaptureSidecarLogs(containerId)) {
            extractAndLogSidecarLogs(containerId);
        }

        String sandboxId = resolveSandboxId(containerId);
        terminateSandboxResources(sandboxId, containerId);
    }

    private boolean shouldCaptureSidecarLogs(String containerId) {
        if (debug) {
            return true;
        }
        try {
            return executionEnvironmentRepository
                    .findByContainerId(containerId)
                    .map(environment -> environment.getStatus() != EnvironmentStatus.CONNECTED)
                    .orElse(true);
        } catch (Exception e) {
            logger.debug("Failed to look up environment for container {}: {}", containerId, e.getMessage());
            return true;
        }
    }

    private String resolveSandboxId(String containerId) {
        // Try to inspect kratis.sandbox.id label from the container
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of(
                            "docker",
                            "inspect",
                            "--format",
                            "{{ index .Config.Labels \"kratis.sandbox.id\" }}",
                            containerId),
                    null,
                    null);
            if (result.exitCode() == 0) {
                String labelVal = new String(result.output()).trim();
                if (!labelVal.isEmpty() && !"<no value>".equals(labelVal)) {
                    return labelVal;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to inspect sandbox id label for container {}: {}", containerId, e.getMessage());
        }

        // If container name follows kratis-sandbox-${envId}
        if (containerId.startsWith("kratis-sandbox-")) {
            return containerId.substring("kratis-sandbox-".length());
        }

        // Fallback: check ExecutionEnvironment repository
        try {
            Optional<ExecutionEnvironment> envOpt = executionEnvironmentRepository.findByContainerId(containerId);
            if (envOpt.isPresent() && envOpt.get().getId() != null) {
                return envOpt.get().getId().toString();
            }
        } catch (Exception e) {
            logger.debug("Failed to look up environment for container {}: {}", containerId, e.getMessage());
        }

        return containerId;
    }

    private void terminateSandboxResources(String sandboxId, String runnerContainerId) {
        // -v matters: docker:dind-rootless declares two VOLUMEs holding its image store, so each
        // sibling leaks gigabytes without it.
        // 1. Remove runner container
        if (runnerContainerId != null && !runnerContainerId.isBlank()) {
            try {
                processExecutor.execute(List.of("docker", "rm", "-f", "-v", runnerContainerId), null, null);
            } catch (IOException | InterruptedException e) {
                logger.error("Failed to terminate runner container: {}", runnerContainerId, e);
            }
        }

        // Also try removing by standard runner name if different
        String runnerName = "kratis-sandbox-" + sandboxId;
        if (!runnerName.equals(runnerContainerId)) {
            try {
                processExecutor.execute(List.of("docker", "rm", "-f", "-v", runnerName), null, null);
            } catch (Exception e) {
                logger.debug("Runner container {} already removed or not found", runnerName);
            }
        }

        // 2. Remove DinD sibling container
        String dindName = "kratis-dind-" + sandboxId;
        try {
            processExecutor.execute(List.of("docker", "rm", "-f", "-v", dindName), null, null);
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to terminate dind container: {}", dindName, e);
        }

        // 3. Remove network
        String networkName = "kratis-net-" + sandboxId;
        try {
            processExecutor.execute(List.of("docker", "network", "rm", networkName), null, null);
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to remove docker network: {}", networkName, e);
        }
    }

    /**
     * Extracts and logs the sidecar's log file from the container before it is
     * removed.
     */
    private void extractAndLogSidecarLogs(String containerId) {
        try {
            Path tempLog = Files.createTempFile("kratis-sidecar-", ".log");
            try {
                ProcessExecutor.ProcessResult cpResult = processExecutor.execute(
                        List.of("docker", "cp", containerId + ":" + SIDECAR_LOG_LOC, tempLog.toString()), null, null);
                if (cpResult.exitCode() == 0) {
                    String logs = Files.readString(tempLog);
                    if (!logs.isBlank()) {
                        logger.info("Sidecar logs for container {}:\n{}", containerId, logs);
                    } else {
                        logger.info("Sidecar log file is empty for container {}", containerId);
                    }
                } else {
                    logger.warn(
                            "Failed to extract sidecar log file from container {} (exit code {}): {}",
                            containerId,
                            cpResult.exitCode(),
                            new String(cpResult.output()));
                }
            } finally {
                Files.deleteIfExists(tempLog);
            }
        } catch (Exception e) {
            logger.warn("Failed to extract container logs for {}: {}", containerId, e.getMessage());
        }
    }

    @Override
    public List<String> getActiveSandboxIds() {
        List<String> command = List.of(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=kratis.instance.id=" + instanceId,
                "--filter",
                "label=kratis.role=runner",
                "--no-trunc",
                "-q");
        try {
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, null);
            if (result.exitCode() != 0) {
                logger.warn("Docker command failed with exit code: {}", result.exitCode());
                return List.of();
            }
            String output = new String(result.output()).trim();
            if (output.isEmpty()) {
                return List.of();
            }
            return List.of(output.split("\\s+"));
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to retrieve running managed containers from Docker host: {}", e.getMessage());
            return List.of();
        }
    }
}
