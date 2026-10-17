package com.kratisai.controlplane.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.LocalDockerSandboxProvider;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.service.SandboxProvisioningService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Real connector and real {@code docker commit} snapshots, with no agent and no LLM. Covers the
 * provider suspend/resume contract and the state a woken connector inherits from its snapshot.
 */
@SpringIntegrationTest
@SlowTest(slotGroup = "E2E_DOCKER_CONTAINER")
class SuspendResumeConnectorIntegrationTest {

    private static final String RUNNER_IMAGE = "kratis-runner-base:latest";
    private static final String UPSTREAM_SCRIPT = "set -e; rm -rf /tmp/seed /tmp/up.git; git init -q -b main /tmp/seed;"
            + " cd /tmp/seed; git config user.email a@b.c; git config user.name t;"
            + " echo 1 > a.txt; git add .; git commit -qm c1;"
            + " echo 2 > b.txt; git add .; git commit -qm c2;"
            + " git checkout -qb develop; echo 3 > d.txt; git add .; git commit -qm c3;"
            + " git checkout -q main; git clone -q --bare /tmp/seed /tmp/up.git";

    @LocalServerPort
    private int port;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private EnvironmentProviderRepository providerRepository;

    @Autowired
    private ExecutionEnvironmentRepository environmentRepository;

    @Autowired
    private LocalDockerSandboxProvider provider;

    @Autowired
    private SandboxProvisioningService provisioningService;

    @Autowired
    private ExecutionEnvironmentService environmentService;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private SandboxProperties sandboxProperties;

    private ExecutionEnvironment environment;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        TestDataFactory.TestContext context = testDataFactory.createUserAndTeam();
        EnvironmentProvider envProvider = providerRepository
                .findByTeamId(context.team().getId())
                .stream()
                .filter(p -> "Default Docker Provider".equals(p.getName()))
                .findFirst()
                .orElseThrow();
        envProvider.setDockerImage(RUNNER_IMAGE);
        envProvider = providerRepository.save(envProvider);

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(context.team());
        env.setName("Sandbox - snapshot contract");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setStatus(EnvironmentStatus.PENDING_CONNECT);
        env.setProvider(envProvider);
        env.setAuthToken(UUID.randomUUID().toString());
        environment = environmentRepository.save(env);
        provider.setConnectUrl("ws://host.docker.internal:" + port + "/ws/env");
    }

    @AfterEach
    void tearDown() {
        provider.setConnectUrl(sandboxProperties.getDocker().getConnectUrl());
        if (environment != null) {
            provider.destroy(environment.getId());
        }
        databaseCleaner.cleanAll();
    }

    @Test
    void suspendKeepsDiskResumeRestoresItAndEachSuspendReplacesTheSnapshot() throws Exception {
        UUID envId = environment.getId();
        String runner = "kratis-sandbox-" + envId;
        String snapshot = "kratis-snapshot-" + envId;

        provider.spawnSandbox(environment);
        dockerExec(runner, "sh", "-c", "echo before-suspend > /kratis/workspace/marker.txt");

        provider.suspend(envId);
        assertThat(containerExists(runner))
                .as("suspend must tear the runtime down")
                .isFalse();
        assertThat(provider.listActiveEnvIds()).doesNotContain(envId);
        assertThat(imageIds(snapshot)).hasSize(1);
        String firstImage = imageIds(snapshot).getFirst();

        provider.resume(environment);
        assertThat(dockerExec(runner, "cat", "/kratis/workspace/marker.txt")).isEqualTo("before-suspend");
        dockerExec(runner, "sh", "-c", "echo second > /kratis/workspace/second.txt");

        provider.suspend(envId);
        assertThat(imageIds(snapshot))
                .as("the superseded snapshot image is removed")
                .hasSize(1);
        assertThat(imageIds(snapshot).getFirst()).isNotEqualTo(firstImage);

        provider.resume(environment);
        assertThat(dockerExec(runner, "cat", "/kratis/workspace/second.txt")).isEqualTo("second");

        provider.destroy(envId);
        assertThat(imageIds(snapshot)).as("destroy removes the snapshot").isEmpty();
        assertThatThrownBy(() -> provider.resume(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No suspended snapshot");
    }

    @Test
    void resumedConnectorRegistersAgainAndInheritsGitStateFromTheSnapshot() throws Exception {
        UUID envId = environment.getId();
        String runner = "kratis-sandbox-" + envId;

        provisioningService.provisionEnvironment(environment, environment.getAuthToken());
        awaitStatus(envId, EnvironmentStatus.CONNECTED);

        runOnConnector(UPSTREAM_SCRIPT);
        environmentRpcClient.send(
                envId,
                new EnvironmentRpcPayload.Checkout(
                        "file:///tmp/up.git",
                        "develop",
                        Map.of(),
                        UUID.randomUUID().toString()));
        Awaitility.await("checkout of develop")
                .atMost(Duration.ofSeconds(60))
                .until(() -> processExecutor
                                .execute(
                                        List.of("docker", "exec", runner, "test", "-f", "/kratis/diff_base_branch"),
                                        null,
                                        null)
                                .exitCode()
                        == 0);
        runOnConnector("echo local > /kratis/workspace/local.txt");
        String headBefore = dockerExec(runner, "git", "-C", "/kratis/workspace", "rev-parse", "HEAD");

        environmentService.sleepEnvironmentInternal(
                environmentRepository.findById(envId).orElseThrow());
        assertThat(environmentRepository.findById(envId).orElseThrow().getStatus())
                .isEqualTo(EnvironmentStatus.SLEEPING);

        provisioningService.reprovisionEnvironment(
                environmentRepository.findById(envId).orElseThrow());
        awaitStatus(envId, EnvironmentStatus.CONNECTED);

        assertThat(dockerExec(runner, "git", "-C", "/kratis/workspace", "rev-parse", "HEAD"))
                .isEqualTo(headBefore);
        assertThat(dockerExec(runner, "cat", "/kratis/diff_base_branch")).isEqualTo("develop");
        String base = dockerExec(runner, "git", "-C", "/kratis/workspace", "merge-base", "origin/develop", "HEAD");
        String root = dockerExec(runner, "git", "-C", "/kratis/workspace", "rev-list", "--max-parents=0", "HEAD");
        assertThat(base)
                .as("the persisted branch must resolve a base that is not the root commit")
                .isNotEqualTo(root);
        assertThat(dockerExec(runner, "git", "-C", "/kratis/workspace", "diff", "--name-only", base))
                .as("no upstream file differs from the base")
                .isEmpty();
        assertThat(dockerExec(runner, "git", "-C", "/kratis/workspace", "ls-files", "--others", "--exclude-standard"))
                .as("only the sandbox-local file is new")
                .isEqualTo("local.txt");
    }

    private void awaitStatus(UUID envId, EnvironmentStatus expected) {
        Awaitility.await("environment " + expected)
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(environmentRepository
                                .findById(envId)
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(expected));
    }

    private void runOnConnector(String command) throws Exception {
        EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                environment.getId(),
                new EnvironmentRpcPayload.Exec(command, false, UUID.randomUUID().toString()));
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(ExecStatus.COMPLETED);
        assertThat(result.exitCode()).isZero();
    }

    private String dockerExec(String container, String... command) throws Exception {
        List<String> full = new ArrayList<>(List.of("docker", "exec", container));
        full.addAll(List.of(command));
        ProcessExecutor.ProcessResult result = processExecutor.execute(full, null, null);
        assertThat(result.exitCode())
                .as("%s: %s", String.join(" ", full), new String(result.output()))
                .isZero();
        return new String(result.output()).trim();
    }

    private boolean containerExists(String name) throws Exception {
        return processExecutor
                        .execute(List.of("docker", "inspect", name), null, null)
                        .exitCode()
                == 0;
    }

    private List<String> imageIds(String repository) throws Exception {
        ProcessExecutor.ProcessResult result =
                processExecutor.execute(List.of("docker", "images", "-q", repository), null, null);
        String output = new String(result.output()).trim();
        return output.isEmpty() ? List.of() : List.of(output.split("\\s+"));
    }
}
