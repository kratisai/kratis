package com.kratisai.controlplane.service;

import static com.kratisai.controlplane.service.LocalDockerSandboxProvider.SIDECAR_LOG_LOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LocalDockerSandboxProviderTest {

    private ProcessExecutor mockProcessExecutor;
    private LocalDockerSandboxProvider provider;

    private static LocalDockerSandboxProvider sandboxProvider(
            ProcessExecutor processExecutor,
            String connectUrl,
            String registryMirror,
            String runnerImage,
            boolean debug) {
        InstanceProperties instanceProperties = new InstanceProperties();
        instanceProperties.setId("unit-test-instance");
        SandboxProperties sandboxProperties = new SandboxProperties();
        sandboxProperties.getDocker().setConnectUrl(connectUrl);
        sandboxProperties.getDocker().setRegistryMirror(registryMirror);
        sandboxProperties.getDocker().setRunnerImage(runnerImage);
        sandboxProperties.getDocker().setDebug(debug);
        return new LocalDockerSandboxProvider(processExecutor, sandboxProperties, instanceProperties);
    }

    private static ExecutionEnvironment newEnvironment(UUID envId) {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        return env;
    }

    @BeforeEach
    void setUp() {
        mockProcessExecutor = mock(ProcessExecutor.class);
        provider = sandboxProvider(
                mockProcessExecutor,
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "kratis-runner-base:latest",
                false);
    }

    private void stubAllCommandsSucceed() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));
    }

    private void verifyRunnerRemoved(UUID envId) throws Exception {
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-sandbox-" + envId)), any(), any());
    }

    @Test
    void testGetProviderType() {
        assertThat(provider.getProviderType()).isEqualTo(ExecutionProviderType.DOCKER);
    }

    @Test
    void testSpawnSandboxRejectsEnvironmentWithoutId() {
        ExecutionEnvironment env = new ExecutionEnvironment();

        assertThatThrownBy(() -> provider.spawnSandbox(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Environment id is required");

        verifyNoInteractions(mockProcessExecutor);
    }

    @Test
    void testSpawnSandboxAndInitializeWorkspaceSuccessDefaultImage() throws Exception {
        UUID envId = UUID.randomUUID();
        ExecutionEnvironment env = newEnvironment(envId);
        env.setProvider(null);

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env);
        provider.initializeWorkspace(envId, "test-token");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("network")
                                && cmd.contains("create")
                                && cmd.contains("kratis.role=network")
                                && cmd.contains("kratis.instance.id=unit-test-instance")),
                        any(),
                        any());

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("kratis.role=dind")
                                && cmd.contains("--security-opt")
                                && cmd.contains("seccomp=unconfined")
                                && cmd.contains("systempaths=unconfined")
                                && cmd.contains("--device")
                                && cmd.contains("/dev/net/tun")
                                && cmd.contains("dockerd-entrypoint.sh")),
                        any(),
                        any());

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("kratis-runner-base:latest")
                                && cmd.contains("sleep")
                                && cmd.contains("infinity")
                                && cmd.contains("kratis.instance.id=unit-test-instance")
                                && cmd.contains("kratis.role=runner")
                                && cmd.stream().anyMatch(arg -> arg.startsWith("DOCKER_HOST=tcp://kratis-dind-"))
                                && cmd.stream()
                                        .anyMatch(arg -> arg.startsWith("TESTCONTAINERS_HOST_OVERRIDE=kratis-dind-"))
                                && cmd.contains("TESTCONTAINERS_RYUK_DISABLED=true")
                                && cmd.contains("TESTCONTAINERS_REUSE_ENABLE=true")
                                && cmd.stream().noneMatch(arg -> arg.equals("kratis.managed=true"))),
                        any(),
                        any());

        // The runner is addressed by its deterministic name; the connector is started
        // with the token passed in as a parameter, and the protocol no longer carries a container id.
        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("exec")
                                && cmd.contains("--token")
                                && cmd.contains("test-token")
                                && cmd.contains("--server-url")
                                && cmd.contains("ws://localhost:8080/ws")
                                && cmd.contains("--mode=sidecar")
                                && cmd.stream().noneMatch(arg -> arg.equals("--container-id"))),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxUsesConfiguredRunnerImage() throws Exception {
        LocalDockerSandboxProvider customImageProvider = sandboxProvider(
                mockProcessExecutor,
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "ghcr.io/kratisai/kratis-runner-base:latest",
                false);

        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        customImageProvider.spawnSandbox(env);

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("ghcr.io/kratisai/kratis-runner-base:latest")
                                && cmd.contains("kratis.role=runner")),
                        any(),
                        any());
    }

    @Test
    void testInitializeWorkspaceAppendsDebugFlagWhenEnabled() throws Exception {
        LocalDockerSandboxProvider debugProvider = sandboxProvider(
                mockProcessExecutor,
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "kratis-runner-base:latest",
                true);

        stubAllCommandsSucceed();

        debugProvider.initializeWorkspace(UUID.randomUUID(), "test-token");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(argThat(cmd -> cmd.contains("--mode=sidecar") && cmd.contains("--debug")), any(), any());
    }

    @Test
    void testInitializeWorkspaceOmitsDebugFlagByDefault() throws Exception {
        stubAllCommandsSucceed();

        provider.initializeWorkspace(UUID.randomUUID(), "test-token");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd ->
                                cmd.contains("--mode=sidecar") && cmd.stream().noneMatch(arg -> arg.equals("--debug"))),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxAddsOnlyPackageManagementCapabilities() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env);

        // Every capability is dropped except the minimum required for package
        // management through sudo (apt-get/dpkg). The connector still runs as the
        // non-root user 1000, so ownership of sandbox directories is established
        // once, at image build time, never at container runtime.
        List<String> expected = List.of(
                "--cap-add=CHOWN",
                "--cap-add=DAC_OVERRIDE",
                "--cap-add=FOWNER",
                "--cap-add=FSETID",
                "--cap-add=KILL",
                "--cap-add=SETGID",
                "--cap-add=SETUID",
                "--cap-add=AUDIT_WRITE");
        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("--cap-drop=ALL")
                                && cmd.contains("--user=1000")
                                && cmd.containsAll(expected)
                                && cmd.stream()
                                        .noneMatch(arg -> arg.startsWith("--cap-add=") && !expected.contains(arg))),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxNeverGrantsDockerEscapePaths() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env);

        // The docker escape boundaries must stay intact even though sudo grants
        // root-in-container: the host docker socket is never mounted, the sandbox
        // is never privileged, and the escape-relevant capabilities stay dropped.
        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("docker")
                                && cmd.contains("run")
                                && cmd.stream().noneMatch(arg -> arg.contains("docker.sock"))
                                && cmd.stream().noneMatch(arg -> arg.equals("--privileged"))
                                && cmd.stream().noneMatch(arg -> arg.equals("--cap-add=SYS_ADMIN"))
                                && cmd.stream().noneMatch(arg -> arg.equals("--cap-add=SYS_PTRACE"))
                                && cmd.stream().noneMatch(arg -> arg.equals("--cap-add=NET_ADMIN"))
                                && cmd.stream().noneMatch(arg -> arg.equals("--cap-add=NET_RAW"))),
                        any(),
                        any());
    }

    @Test
    void testSpawnDindSiblingUnmasksProcWithoutPrivilegeOrExtraCapabilities() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env);

        // systempaths=unconfined must stay the only relaxation. --cap-drop=ALL is asserted absent
        // because rootlesskit's setuid newuidmap needs SETUID/SETGID left in the bounding set.
        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("systempaths=unconfined")
                                && cmd.stream().anyMatch(arg -> arg.startsWith("--host-gateway-ip="))
                                && cmd.stream().noneMatch(arg -> arg.equals("--privileged"))
                                && cmd.stream().noneMatch(arg -> arg.startsWith("--cap-add="))
                                && cmd.stream().noneMatch(arg -> arg.equals("--cap-drop=ALL"))
                                && cmd.stream().noneMatch(arg -> arg.contains("docker.sock"))),
                        any(),
                        any());
    }

    @Test
    void testSpawnDindSiblingSkipsHostGatewayIpWhenNetworkGatewayIsUnknown() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("network") && cmd.contains("inspect")) {
                // An unreadable gateway must not fail provisioning
                return new ProcessExecutor.ProcessResult(1, new byte[0]);
            }
            return new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes());
        });

        provider.spawnSandbox(env);

        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.stream().noneMatch(arg -> arg.startsWith("--host-gateway-ip="))),
                        any(),
                        any());
    }

    @Test
    void isReachable_reflectsTcpConnectivity() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            assertThat(LocalDockerSandboxProvider.isReachable("127.0.0.1", server.getLocalPort()))
                    .isTrue();
        }
        assertThat(LocalDockerSandboxProvider.isReachable("127.0.0.1", 1)).isFalse();
    }

    @Test
    void testInitializeWorkspaceNeverExecutesAsRoot() throws Exception {
        stubAllCommandsSucceed();

        provider.initializeWorkspace(UUID.randomUUID(), "test-token");

        // No command should ever run as root (`-u 0`) inside the sandbox: ownership of
        // /kratis/* is established once, at image build time, and never fixed up (or
        // fixable) at container runtime.
        verify(mockProcessExecutor, never())
                .execute(argThat(cmd -> cmd.contains("-u") && cmd.contains("0")), any(), any());
        // Nor should chown/chmod ever be attempted inside the container.
        verify(mockProcessExecutor, never())
                .execute(argThat(cmd -> cmd.contains("chown") || cmd.contains("chmod")), any(), any());
    }

    @Test
    void testInitializeWorkspaceNeverTrustsAllDirectoriesForGit() throws Exception {
        stubAllCommandsSucceed();

        provider.initializeWorkspace(UUID.randomUUID(), "test-token");

        verify(mockProcessExecutor, never())
                .execute(argThat(cmd -> cmd.contains("git") && cmd.contains("safe.directory")), any(), any());
    }

    @Test
    void testInitializeWorkspaceFailsFastWhenSandboxImageContractViolated() throws Exception {
        UUID envId = UUID.randomUUID();

        // Simulate a non-conformant sandbox image: /kratis/workspace was never
        // pre-created/chowned to the sandbox user at image build time, so the
        // writability check fails (the control plane never fixes ownership at
        // runtime, regardless of the package-management capabilities granted).
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("test -d '/kratis/workspace' && test -w '/kratis/workspace'")) {
                return new ProcessExecutor.ProcessResult(1, "".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        assertThatThrownBy(() -> provider.initializeWorkspace(envId, "test-token"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Sandbox image contract violation")
                .hasMessageContaining("/kratis/workspace")
                .hasMessageContaining("IMAGE BUILD TIME");

        // Must fail fast on the very first directory check, before ever attempting to
        // inject the connector binary via `docker cp` (the diagnostic log extraction
        // inside the failure path is not the connector injection).
        verify(mockProcessExecutor, never())
                .execute(
                        argThat(cmd -> cmd.contains("cp") && cmd.contains("/usr/local/bin/kratis-connector")),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxSuccessCustomImage() throws Exception {
        EnvironmentProvider envProvider = new EnvironmentProvider();
        envProvider.setDockerImage("custom-image:v1");

        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());
        env.setProvider(envProvider);

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env);

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd ->
                                cmd.contains("custom-image:v1") && cmd.contains("sleep") && cmd.contains("infinity")),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxFailureExitCode() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "error message".getBytes()));

        assertThatThrownBy(() -> provider.spawnSandbox(env))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to spawn docker sandbox: error message");
    }

    @Test
    void testSpawnSandboxFailureException() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any())).thenThrow(new IOException("Simulated IO error"));

        assertThatThrownBy(() -> provider.spawnSandbox(env))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to spawn docker sandbox: Simulated IO error");
    }

    @Test
    void testTerminateSandboxSuccessRemovesDeterministicResources() throws Exception {
        UUID envId = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox(envId);

        InOrder inOrder = inOrder(mockProcessExecutor);
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "runner-container-id")), any(), any());
        verifyRunnerRemoved(envId);
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-dind-" + envId)), any(), any());
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-" + envId)), any(), any());

        // Teardown is pure resource removal: log capture is a separate, explicit call.
        verify(mockProcessExecutor, never()).execute(argThat(cmd -> cmd.contains("cp")), any(), any());
    }

    @Test
    void testTerminateSandboxFallsBackToDeterministicRunnerName() throws Exception {
        UUID envId = UUID.randomUUID();
        stubAllCommandsSucceed();

        provider.terminateSandbox(envId);

        verifyRunnerRemoved(envId);
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-dind-" + envId)), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-" + envId)), any(), any());
    }

    @Test
    void testTerminateSandboxRemovesResourcesWhenLabelLookupThrows() throws Exception {
        UUID envId = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenThrow(new IOException("Simulated IO error"));

        // Should handle the exception gracefully without throwing
        provider.terminateSandbox(envId);

        verifyRunnerRemoved(envId);
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-" + envId)), any(), any());
    }

    @Test
    void testTerminateSandboxWithLabelInspection() throws Exception {
        UUID envId = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "custom-runner-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox(envId);

        // The runner is addressed by its label-discovered id; the sibling/network names
        // are always derived from the environment id.
        verify(mockProcessExecutor).execute(eq(List.of("docker", "rm", "-f", "-v", "custom-runner-id")), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-dind-" + envId)), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-" + envId)), any(), any());
    }

    @Test
    void testCaptureSidecarLogsCopiesLogFromResolvedRunner() throws Exception {
        UUID envId = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            if (cmd.size() > 1 && cmd.get(1).equals("cp")) {
                Files.writeString(Path.of(cmd.get(3)), "sidecar log line 1\nsidecar log line 2\n");
                return new ProcessExecutor.ProcessResult(0, "".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.captureSidecarLogs(envId);

        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("runner-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
    }

    @Test
    void testCaptureSidecarLogsFallsBackToDeterministicRunnerName() throws Exception {
        UUID envId = UUID.randomUUID();
        stubAllCommandsSucceed();

        provider.captureSidecarLogs(envId);

        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd -> cmd.get(1).equals("cp")
                                && cmd.get(2).equals("kratis-sandbox-" + envId + ":" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
    }

    @Test
    void testCaptureSidecarLogsDoesNothingWhenNoRunnerExists() throws Exception {
        UUID envId = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "".getBytes()));

        provider.captureSidecarLogs(envId);

        verify(mockProcessExecutor, never()).execute(argThat(cmd -> cmd.contains("cp")), any(), any());
    }

    @Test
    void testListActiveEnvIdsSuccessAndSkipsUnparseableLabels() throws Exception {
        UUID envA = UUID.randomUUID();
        UUID envB = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, (envA + "\nnot-a-uuid\n" + envB + "\n").getBytes()));

        Set<UUID> activeIds = provider.listActiveEnvIds();
        assertThat(activeIds).containsExactlyInAnyOrder(envA, envB);

        // The provider must only ever see runner containers owned by this instance.
        verify(mockProcessExecutor)
                .execute(
                        eq(List.of(
                                "docker",
                                "ps",
                                "-a",
                                "--filter",
                                "label=kratis.instance.id=unit-test-instance",
                                "--filter",
                                "label=kratis.role=runner",
                                "--format",
                                "{{.Label \"kratis.sandbox.id\"}}")),
                        any(),
                        any());
    }

    @Test
    void testListActiveEnvIdsEmptyOutput() throws Exception {
        stubAllCommandsSucceed();

        Set<UUID> activeIds = provider.listActiveEnvIds();
        assertThat(activeIds).isEmpty();
    }

    @Test
    void testListActiveEnvIdsFailureExitCode() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(127, "command not found".getBytes()));

        Set<UUID> activeIds = provider.listActiveEnvIds();
        assertThat(activeIds).isEmpty();
    }

    @Test
    void testListActiveEnvIdsException() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenThrow(new IOException("Simulated IO error"));

        Set<UUID> activeIds = provider.listActiveEnvIds();
        assertThat(activeIds).isEmpty();
    }

    @Test
    void testListSnapshotEnvIdsScopesToThisInstanceSnapshots() throws Exception {
        UUID envA = UUID.randomUUID();
        UUID envB = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, (envA + "\nnot-a-uuid\n" + envB + "\n").getBytes()));

        Set<UUID> snapshotIds = provider.listSnapshotEnvIds();
        assertThat(snapshotIds).containsExactlyInAnyOrder(envA, envB);

        // Removals/reads must stay scoped to this instance's snapshot tags, never every image.
        verify(mockProcessExecutor)
                .execute(
                        eq(List.of(
                                "docker",
                                "images",
                                "--filter",
                                "reference=kratis-snapshot-*",
                                "--filter",
                                "label=kratis.instance.id=unit-test-instance",
                                "--filter",
                                "label=kratis.role=runner",
                                "--format",
                                "{{.Label \"kratis.sandbox.id\"}}")),
                        any(),
                        any());
    }

    @Test
    void testListSnapshotEnvIdsFailureExitCode() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "".getBytes()));

        assertThat(provider.listSnapshotEnvIds()).isEmpty();
    }

    @Test
    void testSpawnSandboxWithCustomRegistryMirror() throws Exception {
        LocalDockerSandboxProvider customMirrorProvider = sandboxProvider(
                mockProcessExecutor,
                "ws://localhost:8080/ws",
                "http://custom-mirror:5001",
                "kratis-runner-base:latest",
                false);

        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        customMirrorProvider.spawnSandbox(env);

        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("--registry-mirror=http://custom-mirror:5001")),
                        any(),
                        any());
    }

    @Test
    void testIsSysctlEnabled() throws Exception {
        Path enabled = Files.createTempFile("kratis-sysctl-enabled", ".txt");
        Files.writeString(enabled, "1\n");
        Path disabled = Files.createTempFile("kratis-sysctl-disabled", ".txt");
        Files.writeString(disabled, "0\n");
        try {
            assertThat(LocalDockerSandboxProvider.isSysctlEnabled(enabled)).isTrue();
            assertThat(LocalDockerSandboxProvider.isSysctlEnabled(disabled)).isFalse();
            assertThat(LocalDockerSandboxProvider.isSysctlEnabled(Path.of("/proc/kratis-missing-sysctl")))
                    .isFalse();
        } finally {
            Files.deleteIfExists(enabled);
            Files.deleteIfExists(disabled);
        }
    }

    @Test
    void testSpawnSandboxGrantsRootlesskitApparmorProfileOnlyWhenUsernsRestricted() throws Exception {
        ExecutionEnvironment env = newEnvironment(UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env);

        boolean restricted = LocalDockerSandboxProvider.apparmorRestrictsUnprivilegedUserns();
        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("apparmor=rootlesskit") == restricted),
                        any(),
                        any());
    }

    @Test
    void testSuspendCommitsSnapshotAndTearsDownSandbox() throws Exception {
        UUID envUuid = UUID.randomUUID();

        AtomicInteger imageLookups = new AtomicInteger();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("inspect") && cmd.stream().anyMatch(arg -> arg.contains("kratis-snapshot-"))) {
                return imageLookups.getAndIncrement() == 0
                        ? new ProcessExecutor.ProcessResult(0, "old-image-id".getBytes())
                        : new ProcessExecutor.ProcessResult(0, "new-image-id".getBytes());
            }
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.suspend(envUuid);

        String snapshotRef = "kratis-snapshot-" + envUuid + ":latest";
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "commit", "runner-container-id", snapshotRef)), any(), any());
        verify(mockProcessExecutor).execute(eq(List.of("docker", "rmi", "-f", "old-image-id")), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "runner-container-id")), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-dind-" + envUuid)), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-" + envUuid)), any(), any());
    }

    @Test
    void testSuspendFailsWhenNoRunnerContainerExists() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "".getBytes()));

        assertThatThrownBy(() -> provider.suspend(envUuid)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testSuspendFallsBackToDeterministicRunnerNameWhenLabelLookupIsEmpty() throws Exception {
        UUID envUuid = UUID.randomUUID();

        AtomicInteger imageLookups = new AtomicInteger();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("inspect") && cmd.stream().anyMatch(arg -> arg.contains("kratis-snapshot-"))) {
                return imageLookups.getAndIncrement() == 0
                        ? new ProcessExecutor.ProcessResult(0, "old-image-id".getBytes())
                        : new ProcessExecutor.ProcessResult(0, "new-image-id".getBytes());
            }
            if (cmd.contains("ps")) {
                // No container carries the sandbox label; the deterministic name resolves instead.
                return new ProcessExecutor.ProcessResult(0, "".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.suspend(envUuid);

        // The container-existence probe uses `docker inspect` on the deterministic name.
        verify(mockProcessExecutor)
                .execute(
                        eq(List.of("docker", "inspect", "--format", "{{.Id}}", "kratis-sandbox-" + envUuid)),
                        any(),
                        any());
        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("commit") && cmd.contains("kratis-sandbox-" + envUuid)),
                        any(),
                        any());
    }

    @Test
    void testIsSandboxRunningTrueWhenInspectReportsRunning() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("{{.State.Running}}")) {
                return new ProcessExecutor.ProcessResult(0, "true\n".getBytes());
            }
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        assertThat(provider.isSandboxRunning(envUuid)).isTrue();
    }

    @Test
    void testIsSandboxRunningFalseWhenInspectReportsStopped() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("{{.State.Running}}")) {
                return new ProcessExecutor.ProcessResult(0, "false\n".getBytes());
            }
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        assertThat(provider.isSandboxRunning(envUuid)).isFalse();
    }

    @Test
    void testIsSandboxRunningFalseWhenNoContainerExists() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "".getBytes()));

        assertThat(provider.isSandboxRunning(envUuid)).isFalse();
    }

    @Test
    void testIsSandboxRunningTrueWhenRuntimeStateUnobservable() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("{{.State.Running}}")) {
                throw new IOException("docker down");
            }
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        assertThat(provider.isSandboxRunning(envUuid)).isTrue();
    }

    @Test
    void testIsSandboxRunningFalseForNullEnvId() {
        assertThat(provider.isSandboxRunning(null)).isFalse();
        verifyNoInteractions(mockProcessExecutor);
    }

    @Test
    void testResumeStartsRunnerFromSnapshotImage() throws Exception {
        UUID envUuid = UUID.randomUUID();
        ExecutionEnvironment env = newEnvironment(envUuid);
        env.setProvider(null);

        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("inspect") && cmd.stream().anyMatch(arg -> arg.contains("kratis-snapshot-"))) {
                return new ProcessExecutor.ProcessResult(0, "snapshot-image-id".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes());
        });

        provider.resume(env);

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("kratis-snapshot-" + envUuid + ":latest")
                                && cmd.contains("kratis.role=runner")
                                && cmd.stream().noneMatch(arg -> arg.equals("kratis-runner-base:latest"))),
                        any(),
                        any());
        verify(mockProcessExecutor, atLeastOnce())
                .execute(argThat(cmd -> cmd.contains("network") && cmd.contains("create")), any(), any());
        verify(mockProcessExecutor, atLeastOnce())
                .execute(argThat(cmd -> cmd.contains("docker:dind-rootless")), any(), any());
    }

    @Test
    void testResumeFailsWhenSnapshotIsMissing() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));
        ExecutionEnvironment env = newEnvironment(envUuid);

        assertThatThrownBy(() -> provider.resume(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No suspended snapshot");

        verify(mockProcessExecutor, never())
                .execute(argThat(cmd -> cmd.contains("network") && cmd.contains("create")), any(), any());
    }

    @Test
    void testDestroyRemovesSnapshotImageForSleepingEnvironment() throws Exception {
        UUID envUuid = UUID.randomUUID();
        stubAllCommandsSucceed();

        provider.destroy(envUuid);

        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rmi", "-f", "kratis-snapshot-" + envUuid + ":latest")), any(), any());
    }

    @Test
    void testDestroyTerminatesRunningResourcesAndRemovesSnapshot() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.destroy(envUuid);

        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "runner-container-id")), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rmi", "-f", "kratis-snapshot-" + envUuid + ":latest")), any(), any());
    }

    @Test
    void testDestroyIsTolerantOfMissingSnapshotAndContainer() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "No such image".getBytes()));

        Assertions.assertThatCode(() -> provider.destroy(envUuid)).doesNotThrowAnyException();
    }

    @Test
    void testDestroyIsIdempotent() throws Exception {
        UUID envUuid = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "No such image".getBytes()));

        provider.destroy(envUuid);
        provider.destroy(envUuid);

        verify(mockProcessExecutor, times(2))
                .execute(eq(List.of("docker", "rmi", "-f", "kratis-snapshot-" + envUuid + ":latest")), any(), any());
    }

    @Test
    void testPruneOrphanNetworksKeepsNetworkWithinGracePeriod() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("network") && cmd.contains("ls")) {
                return new ProcessExecutor.ProcessResult(0, "kratis-net-orphan\n".getBytes());
            }
            if (cmd.contains("network") && cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(0, "0\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.pruneOrphanNetworks(Duration.ofSeconds(60));

        verify(mockProcessExecutor, never())
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-orphan")), any(), any());
    }

    @Test
    void testPruneOrphanNetworksRemovesNetworkAfterGracePeriod() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("network") && cmd.contains("ls")) {
                return new ProcessExecutor.ProcessResult(0, "kratis-net-orphan\n".getBytes());
            }
            if (cmd.contains("network") && cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(0, "0\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        // A zero grace period makes the freshly-tracked network immediately eligible.
        provider.pruneOrphanNetworks(Duration.ZERO);
        provider.pruneOrphanNetworks(Duration.ZERO);

        verify(mockProcessExecutor, atLeastOnce())
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-orphan")), any(), any());
    }

    @Test
    void testPruneOrphanNetworksNeverRemovesNetworkWithAttachedContainers() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("network") && cmd.contains("ls")) {
                return new ProcessExecutor.ProcessResult(0, "kratis-net-inuse\n".getBytes());
            }
            if (cmd.contains("network") && cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(0, "2\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.pruneOrphanNetworks(Duration.ZERO);

        verify(mockProcessExecutor, never())
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-inuse")), any(), any());
    }

    @Test
    void testPruneOrphanNetworksListsOnlyThisInstancesNetworkRoleNetworks() throws Exception {
        stubAllCommandsSucceed();

        provider.pruneOrphanNetworks(Duration.ZERO);

        verify(mockProcessExecutor)
                .execute(
                        eq(List.of(
                                "docker",
                                "network",
                                "ls",
                                "--filter",
                                "label=kratis.instance.id=unit-test-instance",
                                "--filter",
                                "label=kratis.role=network",
                                "--format",
                                "{{.Name}}")),
                        any(),
                        any());
    }

    @Test
    void testTerminateSandboxDisconnectsAttachedContainersBeforeNetworkRemove() throws Exception {
        UUID envId = UUID.randomUUID();
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("network") && cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(
                        0, ("kratis-sandbox-" + envId + " kratis-dind-" + envId + "\n").getBytes());
            }
            if (cmd.contains("ps")) {
                return new ProcessExecutor.ProcessResult(0, "runner-container-id\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox(envId);

        InOrder inOrder = inOrder(mockProcessExecutor);
        inOrder.verify(mockProcessExecutor)
                .execute(
                        eq(List.of(
                                "docker",
                                "network",
                                "disconnect",
                                "-f",
                                "kratis-net-" + envId,
                                "kratis-sandbox-" + envId)),
                        any(),
                        any());
        inOrder.verify(mockProcessExecutor)
                .execute(
                        eq(List.of(
                                "docker",
                                "network",
                                "disconnect",
                                "-f",
                                "kratis-net-" + envId,
                                "kratis-dind-" + envId)),
                        any(),
                        any());
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-" + envId)), any(), any());
    }

    @Test
    void testPruneOrphanNetworksForceDisconnectsRacyAttachBeforeRemove() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("network") && cmd.contains("ls")) {
                return new ProcessExecutor.ProcessResult(0, "kratis-net-orphan\n".getBytes());
            }
            if (cmd.contains("network") && cmd.contains("inspect") && cmd.contains("{{len .Containers}}")) {
                return new ProcessExecutor.ProcessResult(0, "0\n".getBytes());
            }
            if (cmd.contains("network") && cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(0, "stale-container\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.pruneOrphanNetworks(Duration.ZERO);
        provider.pruneOrphanNetworks(Duration.ZERO);

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        eq(List.of("docker", "network", "disconnect", "-f", "kratis-net-orphan", "stale-container")),
                        any(),
                        any());
        verify(mockProcessExecutor, atLeastOnce())
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-orphan")), any(), any());
    }

    @Test
    void testResolveContainerReachableHost() {
        String gateway = "172.18.0.1";
        String resolved = LocalDockerSandboxProvider.resolveContainerReachableHost(gateway);

        if (Files.exists(Path.of("/.dockerenv"))) {
            // Inside a container the bridge gateway points at the docker host, not this
            // process, so the resolved address must differ from the gateway.
            assertThat(resolved).isNotBlank();
        } else {
            assertThat(resolved).isEqualTo(gateway);
        }
    }
}
