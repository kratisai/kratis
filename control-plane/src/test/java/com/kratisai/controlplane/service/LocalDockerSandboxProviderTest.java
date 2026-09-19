package com.kratisai.controlplane.service;

import static com.kratisai.controlplane.service.LocalDockerSandboxProvider.SIDECAR_LOG_LOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LocalDockerSandboxProviderTest {

    private ProcessExecutor mockProcessExecutor;
    private ExecutionEnvironmentRepository mockEnvRepository;
    private LocalDockerSandboxProvider provider;

    @BeforeEach
    void setUp() {
        mockProcessExecutor = mock(ProcessExecutor.class);
        mockEnvRepository = mock(ExecutionEnvironmentRepository.class);
        provider = new LocalDockerSandboxProvider(
                mockProcessExecutor,
                mockEnvRepository,
                "unit-test-instance",
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "kratis-runner-base:latest",
                false);
    }

    @Test
    void testGetProviderType() {
        assertThat(provider.getProviderType()).isEqualTo(ExecutionProviderType.DOCKER);
    }

    @Test
    void testSpawnSandboxAndInitializeWorkspaceSuccessDefaultImage() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));
        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));

        String containerId = provider.spawnSandbox(env, "test-token");
        assertThat(containerId).isEqualTo("mock-container-id");

        provider.initializeWorkspace("mock-container-id");

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

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("exec")
                                && cmd.contains("test-token")
                                && cmd.contains("ws://localhost:8080/ws")
                                && cmd.contains("--container-id")
                                && cmd.contains("mock-container-id")),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxUsesConfiguredRunnerImage() throws Exception {
        LocalDockerSandboxProvider customImageProvider = new LocalDockerSandboxProvider(
                mockProcessExecutor,
                mockEnvRepository,
                "unit-test-instance",
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "ghcr.io/kratisai/kratis-runner-base:latest",
                false);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));
        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));

        customImageProvider.spawnSandbox(env, "test-token");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.contains("ghcr.io/kratisai/kratis-runner-base:latest")
                                && cmd.contains("kratis.role=runner")),
                        any(),
                        any());
    }

    @Test
    void testInitializeWorkspaceAppendsDebugFlagWhenEnabled() throws Exception {
        LocalDockerSandboxProvider debugProvider = new LocalDockerSandboxProvider(
                mockProcessExecutor,
                mockEnvRepository,
                "unit-test-instance",
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "kratis-runner-base:latest",
                true);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        debugProvider.initializeWorkspace("mock-container-id");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(argThat(cmd -> cmd.contains("--mode=sidecar") && cmd.contains("--debug")), any(), any());
    }

    @Test
    void testInitializeWorkspaceOmitsDebugFlagByDefault() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.initializeWorkspace("mock-container-id");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd ->
                                cmd.contains("--mode=sidecar") && cmd.stream().noneMatch(arg -> arg.equals("--debug"))),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxAddsOnlyPackageManagementCapabilities() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env, "test-token");

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
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env, "test-token");

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
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(java.util.UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env, "test-token");

        // systempaths=unconfined must stay the only relaxation. --cap-drop=ALL is asserted absent
        // because rootlesskit's setuid newuidmap needs SETUID/SETGID left in the bounding set.
        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("systempaths=unconfined")
                                && cmd.stream().noneMatch(arg -> arg.equals("--privileged"))
                                && cmd.stream().noneMatch(arg -> arg.startsWith("--cap-add="))
                                && cmd.stream().noneMatch(arg -> arg.equals("--cap-drop=ALL"))
                                && cmd.stream().noneMatch(arg -> arg.contains("docker.sock"))),
                        any(),
                        any());
    }

    @Test
    void testInitializeWorkspaceNeverExecutesAsRoot() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.initializeWorkspace("mock-container-id");

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
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.initializeWorkspace("mock-container-id");

        verify(mockProcessExecutor, never())
                .execute(argThat(cmd -> cmd.contains("git") && cmd.contains("safe.directory")), any(), any());
    }

    @Test
    void testInitializeWorkspaceFailsFastWhenSandboxImageContractViolated() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(null);
        env.setContainerId("mock-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env.setAuthToken("test-token");

        when(mockEnvRepository.findByContainerId("mock-container-id")).thenReturn(Optional.of(env));

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

        assertThatThrownBy(() -> provider.initializeWorkspace("mock-container-id"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Sandbox image contract violation")
                .hasMessageContaining("/kratis/workspace")
                .hasMessageContaining("IMAGE BUILD TIME");

        // Must fail fast on the very first directory check, before ever attempting to
        // inject the connector binary via `docker cp` (the log-extraction `docker cp`
        // inside terminateSandbox is not the connector injection).
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

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setProvider(envProvider);

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        String containerId = provider.spawnSandbox(env, "test-token");
        assertThat(containerId).isEqualTo("mock-container-id");

        verify(mockProcessExecutor, atLeastOnce())
                .execute(
                        argThat(cmd ->
                                cmd.contains("custom-image:v1") && cmd.contains("sleep") && cmd.contains("infinity")),
                        any(),
                        any());
    }

    @Test
    void testSpawnSandboxFailureExitCode() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, "error message".getBytes()));

        assertThatThrownBy(() -> provider.spawnSandbox(env, "test-token"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to spawn docker sandbox: error message");
    }

    @Test
    void testSpawnSandboxFailureException() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();

        when(mockProcessExecutor.execute(any(), any(), any())).thenThrow(new IOException("Simulated IO error"));

        assertThatThrownBy(() -> provider.spawnSandbox(env, "test-token"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to spawn docker sandbox: Simulated IO error");
    }

    @Test
    void testTerminateSandboxSuccess() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.terminateSandbox("test-container-id");

        // Sidecar logs are extracted via docker cp before the container is removed
        InOrder inOrder = inOrder(mockProcessExecutor);
        inOrder.verify(mockProcessExecutor)
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
        inOrder.verify(mockProcessExecutor)
                .execute(
                        eq(List.of(
                                "docker",
                                "inspect",
                                "--format",
                                "{{ index .Config.Labels \"kratis.sandbox.id\" }}",
                                "test-container-id")),
                        any(),
                        any());
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "test-container-id")), any(), any());
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-sandbox-test-container-id")), any(), any());
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-dind-test-container-id")), any(), any());
        inOrder.verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-test-container-id")), any(), any());
    }

    @Test
    void testTerminateSandboxLogsSidecarContentWhenCpSucceeds() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.get(1).equals("cp")) {
                Files.writeString(Path.of(cmd.get(3)), "sidecar log line 1\nsidecar log line 2\n");
                return new ProcessExecutor.ProcessResult(0, "".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox("test-container-id");

        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
        verify(mockProcessExecutor, times(1))
                .execute(eq(List.of("docker", "rm", "-f", "-v", "test-container-id")), any(), any());
    }

    @Test
    void testTerminateSandboxStillRemovesContainerWhenCpFailsWithExitCode() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.get(1).equals("cp")) {
                return new ProcessExecutor.ProcessResult(1, "no such file".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox("test-container-id");

        verify(mockProcessExecutor, times(1))
                .execute(eq(List.of("docker", "rm", "-f", "-v", "test-container-id")), any(), any());
    }

    @Test
    void testTerminateSandboxStillRemovesContainerWhenCpThrows() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.get(1).equals("cp")) {
                throw new IOException("Simulated IO error");
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox("test-container-id");

        verify(mockProcessExecutor, times(1))
                .execute(eq(List.of("docker", "rm", "-f", "-v", "test-container-id")), any(), any());
    }

    @Test
    void testTerminateSandboxException() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.get(1).equals("cp")) {
                return new ProcessExecutor.ProcessResult(0, "".getBytes());
            }
            throw new IOException("Simulated IO error");
        });

        // Should handle the exception gracefully without throwing
        provider.terminateSandbox("test-container-id");

        verify(mockProcessExecutor, times(1))
                .execute(eq(List.of("docker", "rm", "-f", "-v", "test-container-id")), any(), any());
    }

    @Test
    void testTerminateSandboxSkipsSidecarLogExtractionWhenEnvironmentConnected() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setContainerId("test-container-id");
        env.setStatus(EnvironmentStatus.CONNECTED);
        when(mockEnvRepository.findByContainerId("test-container-id")).thenReturn(Optional.of(env));

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.terminateSandbox("test-container-id");

        // The sidecar already streamed its output live over env.output, so a clean
        // (CONNECTED) teardown never copies the on-disk log.
        verify(mockProcessExecutor, never())
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
        verify(mockProcessExecutor, times(1))
                .execute(eq(List.of("docker", "rm", "-f", "-v", "test-container-id")), any(), any());
    }

    @Test
    void testTerminateSandboxCapturesSidecarLogsWhenEnvironmentDisconnected() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setContainerId("test-container-id");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        when(mockEnvRepository.findByContainerId("test-container-id")).thenReturn(Optional.of(env));

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.terminateSandbox("test-container-id");

        // A disconnected sidecar never streamed its output; fall back to the on-disk log.
        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
    }

    @Test
    void testTerminateSandboxCapturesSidecarLogsWhenEnvironmentPendingReconnect() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setContainerId("test-container-id");
        env.setStatus(EnvironmentStatus.PENDING_RECONNECT);
        when(mockEnvRepository.findByContainerId("test-container-id")).thenReturn(Optional.of(env));

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.terminateSandbox("test-container-id");

        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
    }

    @Test
    void testTerminateSandboxCapturesSidecarLogsWhenEnvironmentUnknown() throws Exception {
        when(mockEnvRepository.findByContainerId("test-container-id")).thenReturn(Optional.empty());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        provider.terminateSandbox("test-container-id");

        // An orphan with no database record has unknown provenance, so its log is captured.
        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
    }

    @Test
    void testTerminateSandboxCapturesSidecarLogsInDebugMode() throws Exception {
        LocalDockerSandboxProvider debugProvider = new LocalDockerSandboxProvider(
                mockProcessExecutor,
                mockEnvRepository,
                "unit-test-instance",
                "ws://localhost:8080/ws",
                "http://host.docker.internal:5001",
                "kratis-runner-base:latest",
                true);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setContainerId("test-container-id");
        env.setStatus(EnvironmentStatus.CONNECTED);
        when(mockEnvRepository.findByContainerId("test-container-id")).thenReturn(Optional.of(env));

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        debugProvider.terminateSandbox("test-container-id");

        // Debug mode always captures the sidecar log regardless of connection health.
        verify(mockProcessExecutor, times(1))
                .execute(
                        argThat(cmd ->
                                cmd.get(1).equals("cp") && cmd.get(2).equals("test-container-id:" + SIDECAR_LOG_LOC)),
                        any(),
                        any());
    }

    @Test
    void testGetActiveSandboxIdsSuccess() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "id1\nid2\n".getBytes()));

        List<String> activeIds = provider.getActiveSandboxIds();
        assertThat(activeIds).containsExactly("id1", "id2");

        // The collector must only ever see runner containers owned by this instance.
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
                                "--no-trunc",
                                "-q")),
                        any(),
                        any());
    }

    @Test
    void testGetActiveSandboxIdsEmptyOutput() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "".getBytes()));

        List<String> activeIds = provider.getActiveSandboxIds();
        assertThat(activeIds).isEmpty();
    }

    @Test
    void testGetActiveSandboxIdsFailureExitCode() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(127, "command not found".getBytes()));

        List<String> activeIds = provider.getActiveSandboxIds();
        assertThat(activeIds).isEmpty();
    }

    @Test
    void testSpawnSandboxWithCustomRegistryMirror() throws Exception {
        LocalDockerSandboxProvider customMirrorProvider = new LocalDockerSandboxProvider(
                mockProcessExecutor,
                mockEnvRepository,
                "unit-test-instance",
                "ws://localhost:8080/ws",
                "http://custom-mirror:5001",
                "kratis-runner-base:latest",
                false);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(java.util.UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        customMirrorProvider.spawnSandbox(env, "test-token");

        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("--registry-mirror=http://custom-mirror:5001")),
                        any(),
                        any());
    }

    @Test
    void testTerminateSandboxWithLabelInspection() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(0, "custom-sandbox-uuid\n".getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        provider.terminateSandbox("test-container-id");

        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "rm", "-f", "-v", "kratis-dind-custom-sandbox-uuid")), any(), any());
        verify(mockProcessExecutor)
                .execute(eq(List.of("docker", "network", "rm", "kratis-net-custom-sandbox-uuid")), any(), any());
    }

    @Test
    void testGetActiveSandboxIdsException() throws Exception {
        when(mockProcessExecutor.execute(any(), any(), any())).thenThrow(new IOException("Simulated IO error"));

        List<String> activeIds = provider.getActiveSandboxIds();
        assertThat(activeIds).isEmpty();
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
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(java.util.UUID.randomUUID());

        when(mockProcessExecutor.execute(any(), any(), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "mock-container-id\n".getBytes()));

        provider.spawnSandbox(env, "test-token");

        boolean restricted = LocalDockerSandboxProvider.apparmorRestrictsUnprivilegedUserns();
        verify(mockProcessExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("docker:dind-rootless")
                                && cmd.contains("apparmor=rootlesskit") == restricted),
                        any(),
                        any());
    }
}
