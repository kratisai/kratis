package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end sandbox lifecycle integration test verifying rootless DinD sibling orchestration,
 * private bridge network provisioning, runner container Docker/Testcontainers configuration,
 * in-sandbox container command execution simulation, and deterministic multi-resource cleanup.
 */
@SpringIntegrationTest
@SlowTest
class SandboxTestcontainersIntegrationTest {

    @Autowired
    private ExecutionEnvironmentRepository environmentRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        team = testDataFactory.createUserAndTeam().team();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testSandboxOrchestratesDinDNetworkAndRunnerCleanly() throws Exception {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(team);
        env.setName("Testcontainers Sandbox");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        env = environmentRepository.save(env);

        UUID envId = env.getId();
        String sandboxId = envId.toString();
        String expectedNet = "kratis-net-" + sandboxId;
        String expectedDind = "kratis-dind-" + sandboxId;
        String expectedRunner = "kratis-sandbox-" + sandboxId;

        ProcessExecutor mockExecutor = mock(ProcessExecutor.class);
        List<List<String>> executedCommands = new CopyOnWriteArrayList<>();

        when(mockExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            executedCommands.add(new ArrayList<>(cmd));

            if (cmd.contains("network") && cmd.contains("create")) {
                return new ProcessExecutor.ProcessResult(0, "mock-net-id\n".getBytes());
            }
            if (cmd.contains("docker:dind-rootless")) {
                return new ProcessExecutor.ProcessResult(0, "mock-dind-id\n".getBytes());
            }
            if (cmd.contains("kratis-runner-base:latest")) {
                return new ProcessExecutor.ProcessResult(0, expectedRunner.getBytes());
            }
            if (cmd.contains("inspect")) {
                return new ProcessExecutor.ProcessResult(0, sandboxId.getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        LocalDockerSandboxProvider provider = new LocalDockerSandboxProvider(
                mockExecutor,
                environmentRepository,
                "kratis-e2e-instance",
                "ws://host.docker.internal:8080/ws/env",
                "http://host.docker.internal:5001",
                "kratis-runner-base:latest",
                false);

        // 1. Spawn Sandbox topology: Network -> DinD sibling -> Runner
        String spawnedContainerId = provider.spawnSandbox(env, "auth-token-xyz");
        assertThat(spawnedContainerId).isEqualTo(expectedRunner);

        // Verify network creation
        verify(mockExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("network")
                                && cmd.contains("create")
                                && cmd.contains(expectedNet)
                                && cmd.contains("kratis.role=network")
                                && cmd.contains("kratis.sandbox.id=" + sandboxId)),
                        any(),
                        any());

        // Verify DinD sibling execution
        verify(mockExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("run")
                                && cmd.contains(expectedDind)
                                && cmd.contains("--network")
                                && cmd.contains(expectedNet)
                                && cmd.contains("seccomp=unconfined")
                                && cmd.contains("--device")
                                && cmd.contains("/dev/net/tun")
                                && cmd.contains("DOCKER_TLS_CERTDIR=")
                                && cmd.contains("--registry-mirror=http://host.docker.internal:5001")
                                && cmd.contains("kratis.role=dind")
                                && cmd.contains("docker:dind-rootless")),
                        any(),
                        any());

        // Verify Runner execution on same network with TestContainers environment variables
        verify(mockExecutor)
                .execute(
                        argThat(cmd -> cmd.contains("run")
                                && cmd.contains(expectedRunner)
                                && cmd.contains("--network")
                                && cmd.contains(expectedNet)
                                && cmd.contains("DOCKER_HOST=tcp://" + expectedDind + ":2375")
                                && cmd.contains("TESTCONTAINERS_HOST_OVERRIDE=" + expectedDind)
                                && cmd.contains("TESTCONTAINERS_RYUK_DISABLED=true")
                                && cmd.contains("kratis.role=runner")),
                        any(),
                        any());

        // 2. Simulate in-sandbox container command execution (docker ps / docker run)
        List<String> inSandboxDockerCmd = List.of(
                "docker", "exec", expectedRunner, "docker", "run", "--rm", "alpine", "echo", "testcontainers-ready");
        mockExecutor.execute(inSandboxDockerCmd, null, null);

        // 3. Query active sandboxes: must return runner only (never DinD)
        doReturn(new ProcessExecutor.ProcessResult(0, (expectedRunner + "\n").getBytes()))
                .when(mockExecutor)
                .execute(argThat(cmd -> cmd.contains("ps") && cmd.contains("label=kratis.role=runner")), any(), any());

        List<String> activeSandboxes = provider.getActiveSandboxIds();
        assertThat(activeSandboxes).containsExactly(expectedRunner);

        // 4. Teardown sandbox: deterministic removal of runner, DinD sibling, and network
        provider.terminateSandbox(spawnedContainerId);

        verify(mockExecutor, atLeastOnce()).execute(eq(List.of("docker", "rm", "-f", expectedRunner)), any(), any());
        verify(mockExecutor, atLeastOnce()).execute(eq(List.of("docker", "rm", "-f", expectedDind)), any(), any());
        verify(mockExecutor, atLeastOnce()).execute(eq(List.of("docker", "network", "rm", expectedNet)), any(), any());
    }
}
