package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SandboxExecutionScenarioFactory;
import com.kratisai.controlplane.testsupport.SandboxExecutionScenarioFactory.ExecutionScenario;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A resumed sandbox starts a new connector process that re-registers with the same token. The
 * scripted sidecar stands in for that process; these tests pin what the control plane sends it.
 */
@SpringIntegrationTest
class SandboxResumeWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SandboxExecutionRepository executionRepository;

    @Autowired
    private SandboxExecutionActivityRepository activityRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionScenarioFactory scenarioFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private EnvironmentSessionRegistry sessionRegistry;

    private TestDataFactory.AuthContext auth;
    private ChatEntity chat;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        auth = testDataFactory.createProvisionedContext();
        chat = testDataFactory.createChat(auth.team(), auth.user(), "Resume Test Session");
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void reregisteringWithIdleExecutionRelaunchesAgentWithoutCheckoutOrTaskPrompt() throws Exception {
        ExecutionScenario scenario = runFirstLifetimeToIdle("relaunch-plan", "relaunch-repo");

        try (WsPair second =
                WsPair.connectSidecar(port, scenario, sidecar -> sidecar.withPersistedSession("persisted-session")
                        .expectTrigger("env.launch_acp_agent", 1))) {
            assertThat(second.sidecar().awaitTrigger("env.launch_acp_agent", 15, TimeUnit.SECONDS))
                    .isTrue();

            List<String> methods = methodsReceived(second.sidecar());
            assertThat(methods)
                    .as("a new connector holds no credentials, so identity is redeployed before the relaunch")
                    .contains("env.registerGitIdentity", "env.exec", "env.launch_acp_agent");
            assertThat(methods.indexOf("env.registerGitIdentity")).isLessThan(methods.indexOf("env.launch_acp_agent"));
            assertThat(methods.indexOf("env.exec")).isLessThan(methods.indexOf("env.launch_acp_agent"));

            // The checkout and the task prompt already ran in the first lifetime: observe for a
            // bounded window that neither is sent again.
            Awaitility.await("no checkout or task prompt after relaunch")
                    .during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> second.sidecar().receivedMessages().stream()
                            .noneMatch(m -> m.contains("\"method\":\"env.checkout\"")
                                    || m.contains("\"method\":\"env.acp_prompt\"")));
            assertThat(statusOf(scenario.executionId()))
                    .as("a re-attached session stays IDLE and steerable")
                    .isEqualTo(SandboxExecutionStatus.IDLE);
        }
    }

    @Test
    void relaunchSetupCommandsCarryNoUnresolvedLaunchVariables() throws Exception {
        ExecutionScenario scenario = runFirstLifetimeToIdle("vars-plan", "vars-repo");

        try (WsPair second =
                WsPair.connectSidecar(port, scenario, sidecar -> sidecar.withPersistedSession("persisted-session")
                        .expectTrigger("env.launch_acp_agent", 1))) {
            assertThat(second.sidecar().awaitTrigger("env.launch_acp_agent", 15, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(second.sidecar().receivedMessages().stream()
                            .filter(m -> m.contains("\"method\":\"env.exec\""))
                            .toList())
                    .isNotEmpty()
                    .noneMatch(m -> m.contains("${VIRTUAL_KEY}") || m.contains("${LLM_BASE_URL}"));
        }
    }

    @Test
    void reregisteringWithNonIdleExecutionOnlyRedeploysGitCredentials() throws Exception {
        ExecutionScenario scenario = runFirstLifetimeToIdle("non-idle-plan", "non-idle-repo");
        SandboxExecution execution =
                executionRepository.findById(scenario.executionId()).orElseThrow();
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        executionRepository.save(execution);

        try (WsPair second =
                WsPair.connectSidecar(port, scenario, sidecar -> sidecar.expectTrigger("env.registerGitIdentity", 1))) {
            assertThat(second.sidecar().awaitTrigger("env.registerGitIdentity", 15, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(second.sidecar().receivedMessages())
                    .as("a finished execution is not relaunched")
                    .noneMatch(m -> m.contains("\"method\":\"env.launch_acp_agent\""))
                    .noneMatch(m -> m.contains("\"method\":\"env.checkout\""));
        }
    }

    @Test
    void failedRelaunchMarksTheExecutionFailedWithAVisibleError() throws Exception {
        ExecutionScenario scenario = runFirstLifetimeToIdle("fail-plan", "fail-repo");

        try (WsPair second = WsPair.connectSidecar(
                port, scenario, sidecar -> sidecar.withLaunchFailure("agent binary missing from snapshot"))) {
            Awaitility.await("execution fails when the relaunch fails")
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(() ->
                            assertThat(statusOf(scenario.executionId())).isEqualTo(SandboxExecutionStatus.FAILED));
            assertThat(activityRepository.findByExecutionIdAndActivityTypeOrderBySequenceAsc(
                            scenario.executionId(), ActivityType.ERROR))
                    .as("the failed relaunch is visible in the activity log")
                    .isNotEmpty();
            assertThat(second.sidecar().receivedMessages()).isNotEmpty();
        }
    }

    private ExecutionScenario runFirstLifetimeToIdle(String canvasId, String repoName) throws Exception {
        ExecutionScenario scenario =
                scenarioFactory.startOnConnector(auth, chat, "Resume Connector", canvasId, repoName);
        try (WsPair first = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.expectTrigger("execution_output", 1),
                sidecar -> sidecar.expectTrigger("env.acp_prompt", 1))) {
            assertThat(first.sidecar().awaitTrigger("env.acp_prompt", 15, TimeUnit.SECONDS))
                    .isTrue();
            Awaitility.await("first lifetime reaches IDLE")
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> assertThat(statusOf(scenario.executionId())).isEqualTo(SandboxExecutionStatus.IDLE));
        }
        Awaitility.await("first connector session removed")
                .atMost(Duration.ofSeconds(10))
                .until(() -> sessionRegistry.getSessionForEnvironment(scenario.envId()) == null);
        return scenario;
    }

    private SandboxExecutionStatus statusOf(UUID executionId) {
        return executionRepository.findById(executionId).orElseThrow().getStatus();
    }

    private static List<String> methodsReceived(SidecarWebSocketFixture sidecar) {
        return sidecar.receivedMessages().stream()
                .map(SandboxResumeWebSocketIntegrationTest::methodOf)
                .toList();
    }

    private static String methodOf(String payload) {
        int idx = payload.indexOf("\"method\":\"");
        if (idx < 0) {
            return "";
        }
        int start = idx + "\"method\":\"".length();
        return payload.substring(start, payload.indexOf('"', start));
    }
}
