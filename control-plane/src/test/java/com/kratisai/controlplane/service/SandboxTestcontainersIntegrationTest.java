package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        env = environmentRepository.save(env);

        UUID envId = env.getId();
        String sandboxId = envId.toString();
        String expectedNet = "kratis-net-" + sandboxId;
        String expectedDind = "kratis-dind-" + sandboxId;
        String expectedRunner = "kratis-sandbox-" + sandboxId;

        ProcessExecutor mockExecutor = mock(ProcessExecutor.class);

        java.util.Set<String> removed = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        when(mockExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("rm")) {
                removed.add(cmd.getLast());
                return new ProcessExecutor.ProcessResult(0, "".getBytes());
            }
            if (cmd.contains("network") && cmd.contains("create")) {
                return new ProcessExecutor.ProcessResult(0, "mock-net-id\n".getBytes());
            }
            if (cmd.contains("docker:dind-rootless")) {
                return new ProcessExecutor.ProcessResult(0, "mock-dind-id\n".getBytes());
            }
            if (cmd.contains("kratis-runner-base:latest")) {
                return new ProcessExecutor.ProcessResult(0, expectedRunner.getBytes());
            }
            if (cmd.stream().anyMatch(arg -> arg.contains("index .Config.Labels") || arg.contains("index .Labels"))) {
                // The dind is removed mid-teardown; afterwards its label lookup reports the
                // instance only for resources that were never removed.
                String target = cmd.getLast();
                if (removed.contains(target)) {
                    return new ProcessExecutor.ProcessResult(1, "".getBytes());
                }
                return new ProcessExecutor.ProcessResult(0, "kratis-e2e-instance\n".getBytes());
            }
            if (cmd.contains("ps")) {
                // Labels vanish with the `rm`: the mock host forgets removed ids everywhere.
                String label = cmd.stream()
                        .filter(arg -> arg.startsWith("label=kratis.sandbox.id="))
                        .map(arg -> arg.substring("label=kratis.sandbox.id=".length()))
                        .findFirst()
                        .orElse("");
                if (!label.isBlank() && removed.stream().anyMatch(r -> r.contains(label))) {
                    return new ProcessExecutor.ProcessResult(0, "".getBytes());
                }
                if (cmd.contains("label=kratis.role=runner")) {
                    return removed.contains(expectedRunner)
                            ? new ProcessExecutor.ProcessResult(0, "".getBytes())
                            : new ProcessExecutor.ProcessResult(0, (expectedRunner + "\n").getBytes());
                }
            }
            if (cmd.contains("inspect")) {
                return removed.contains(cmd.getLast())
                        ? new ProcessExecutor.ProcessResult(1, "".getBytes())
                        : new ProcessExecutor.ProcessResult(0, sandboxId.getBytes());
            }
            return new ProcessExecutor.ProcessResult(0, "".getBytes());
        });

        InstanceProperties instanceProperties = new InstanceProperties();
        instanceProperties.setId("kratis-e2e-instance");
        SandboxProperties sandboxProperties = new SandboxProperties();
        sandboxProperties.getDocker().setConnectUrl("ws://host.docker.internal:8080/ws/env");
        sandboxProperties.getDocker().setRegistryMirror("http://host.docker.internal:5001");
        sandboxProperties.getDocker().setRunnerImage("kratis-runner-base:latest");
        LocalDockerSandboxProvider provider =
                new LocalDockerSandboxProvider(mockExecutor, sandboxProperties, instanceProperties);

        // 1. Spawn Sandbox topology: Network -> DinD sibling -> Runner
        provider.spawnSandbox(env);

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
                                && cmd.contains("systempaths=unconfined")
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
                                && cmd.contains("TESTCONTAINERS_REUSE_ENABLE=true")
                                && cmd.contains("kratis.role=runner")),
                        any(),
                        any());

        // 2. Simulate in-sandbox container command execution (docker ps / docker run)
        List<String> inSandboxDockerCmd = List.of(
                "docker", "exec", expectedRunner, "docker", "run", "--rm", "alpine", "echo", "testcontainers-ready");
        mockExecutor.execute(inSandboxDockerCmd, null, null);

        // 3. Query active sandboxes: the provider reports environment ids, never raw container ids
        doAnswer(invocation -> removed.contains(expectedRunner)
                        ? new ProcessExecutor.ProcessResult(0, "".getBytes())
                        : new ProcessExecutor.ProcessResult(0, (sandboxId + "\n").getBytes()))
                .when(mockExecutor)
                .execute(argThat(cmd -> cmd.contains("ps") && cmd.contains("label=kratis.role=runner")), any(), any());

        Set<UUID> activeEnvIds = provider.listActiveEnvIds();
        assertThat(activeEnvIds).containsExactly(envId);

        // 4. Teardown sandbox: deterministic removal of runner, DinD sibling, and network
        provider.terminateSandbox(envId);

        // -v matters: the sibling declares two VOLUMEs, so omitting it leaks its image store.
        verify(mockExecutor, atLeastOnce())
                .execute(
                        argThat(cmd -> cmd.size() == 5
                                && cmd.subList(0, 4).equals(List.of("docker", "rm", "-f", "-v"))
                                && !cmd.get(4).equals(expectedDind)),
                        any(),
                        any());
        verify(mockExecutor, atLeastOnce())
                .execute(eq(List.of("docker", "rm", "-f", "-v", expectedDind)), any(), any());
        verify(mockExecutor, atLeastOnce()).execute(eq(List.of("docker", "network", "rm", expectedNet)), any(), any());
    }
}
