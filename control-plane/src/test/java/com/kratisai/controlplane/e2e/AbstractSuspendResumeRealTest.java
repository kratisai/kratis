package com.kratisai.controlplane.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.SandboxExecutionUsage;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.testsupport.llm.HttpRequestMatcher;
import com.kratisai.controlplane.testsupport.llm.LlmMockScenarios;
import com.kratisai.controlplane.testsupport.llm.LlmResponseBuilders;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-sandbox suspend/resume scenarios: a real connector, a real {@code docker commit} snapshot, and
 * a real git server whose repository has several commits and a non-default branch. The agent turn
 * runs through the shared OpenCode flow; each test then resumes the sleeping sandbox and asserts that
 * the disk, the git state, the diff and the LiteLLM key behave as a woken sandbox must.
 */
abstract class AbstractSuspendResumeRealTest extends AbstractAgentExecutionRealTest {

    protected static final String FIXTURE_TOKEN = "kratis-fixture-pat-token";
    protected static final String WORKSPACE = "/kratis/workspace";
    private static final String RESUMED_FILE = "resume_added.txt";
    private static final String AGENT_FILE = "kratis_task.sh";

    protected static GitFixtureContainer gitFixture;

    @Autowired
    private ProcessExecutor suspendProcessExecutor;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    @Autowired
    private ExecutionEnvironmentService environmentService;

    @Autowired
    private ExecutionEnvironmentRepository environmentRepository;

    @Autowired
    private SandboxExecutionRepository executionRepository;

    @Autowired
    private ExecutionDiffSnapshotRepository diffSnapshotRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startGitFixture() {
        gitFixture = new GitFixtureContainer(FIXTURE_TOKEN);
        gitFixture.start();
    }

    @AfterAll
    static void stopGitFixture() {
        if (gitFixture != null) {
            gitFixture.stop();
        }
    }

    protected abstract String targetBranch();

    @Override
    protected String getRepositoryUrl() {
        return gitFixture.getGitHttpUrl();
    }

    @Override
    protected String getRepositoryBranch() {
        return targetBranch();
    }

    @Override
    protected RepoCredential createRepositoryCredential() {
        return testDataFactory.createCredential(testContext.team(), "Fixture PAT", CredentialType.PAT, FIXTURE_TOKEN);
    }

    @Test
    void resumedSandboxKeepsDiskGitStateAndReportsOnlyLocalChanges() throws Exception {
        runFirstTurnAndSleep();
        UUID envId = spawnedEnvironmentId();
        UUID executionId = latestExecution(envId).getId();
        String snapshotImage = "kratis-snapshot-" + envId + ":latest";
        assertThat(inspectImage(snapshotImage))
                .as("sleep must leave a snapshot image to resume from")
                .isTrue();

        Map<String, String> beforeSleep = readSnapshotState(snapshotImage);
        assertThat(beforeSleep.get("session"))
                .as("the connector must persist the ACP session id inside the snapshot")
                .isNotBlank();
        assertThat(beforeSleep.get("base"))
                .as("the connector must persist the checked-out diff base branch inside the snapshot")
                .isEqualTo(targetBranch());
        List<String> usageKeysBefore = usageKeys(executionId);
        assertThat(usageKeysBefore).hasSize(1);
        long llmRequestsBeforeResume = wireMockLlmServer.getTotalMatchedRequests();
        int acpInitializedBefore = acpInitializedCount();

        resumeViaRest(envId);
        awaitResumed(envId, acpInitializedBefore);

        Map<String, String> afterResume = readLiveState();
        assertThat(afterResume)
                .as("the resumed disk must equal the suspended disk (HEAD, origin, session, base, agent file)")
                .isEqualTo(beforeSleep);

        assertThat(usageKeys(executionId))
                .as("resume must open a second usage segment with a fresh virtual key")
                .hasSize(2)
                .startsWith(usageKeysBefore.getFirst());
        List<String> usageKeysAfter = usageKeys(executionId);
        assertThat(usageKeysAfter.get(1)).isNotEqualTo(usageKeysAfter.get(0));
        assertThat(activeVirtualKeys(envId)).containsExactly(usageKeysAfter.get(1));

        Awaitility.await("no LLM replay after resume")
                .during(Duration.ofSeconds(8))
                .atMost(Duration.ofSeconds(20))
                .until(() -> wireMockLlmServer.getTotalMatchedRequests() == llmRequestsBeforeResume);
        assertThat(latestExecution(envId).getStatus())
                .as("a re-attached session stays IDLE and steerable; the task prompt is not re-dispatched")
                .isEqualTo(SandboxExecutionStatus.IDLE);

        // Credentials live only in connector memory, so a woken connector must have them redeployed.
        // Exec children inherit the connector's credential environment; a bare docker exec does not.
        runInSandbox(executionId, "git -C " + WORKSPACE + " fetch origin");

        assertDiffIsLocalOnly(executionId, RESUMED_FILE);
        assertThat(createDockerVerifier()
                        .executeInContainer(
                                "sh",
                                "-c",
                                "git -C " + WORKSPACE + " diff --name-only $(git -C " + WORKSPACE
                                        + " merge-base origin/" + targetBranch() + " HEAD)")
                        .exitCode())
                .isZero();
    }

    @Test
    void secondSuspendResumeCycleKeepsStateAndLeavesOneSnapshot() throws Exception {
        runFirstTurnAndSleep();
        UUID envId = spawnedEnvironmentId();
        UUID executionId = latestExecution(envId).getId();
        String snapshotImage = "kratis-snapshot-" + envId + ":latest";
        String headBefore = readSnapshotState(snapshotImage).get("head");

        int acpInitialized = acpInitializedCount();
        resumeViaRest(envId);
        awaitResumed(envId, acpInitialized);
        runInSandbox(executionId, "echo second > " + WORKSPACE + "/second_cycle.txt");

        environmentService.sleepEnvironmentInternal(
                environmentRepository.findById(envId).orElseThrow());
        Awaitility.await("second sleep").atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                        environmentRepository.findById(envId).orElseThrow().getStatus())
                .isEqualTo(EnvironmentStatus.SLEEPING));
        awaitUsageSegmentSealed(envId);
        assertThat(readSnapshotState(snapshotImage).get("head")).isEqualTo(headBefore);
        assertThat(dockerOutput("docker", "images", "-q", "kratis-snapshot-" + envId))
                .as("committing a new snapshot must remove the superseded image")
                .hasLineCount(1);

        acpInitialized = acpInitializedCount();
        resumeViaRest(envId);
        awaitResumed(envId, acpInitialized);
        assertThat(createDockerVerifier().fileExists(WORKSPACE + "/second_cycle.txt"))
                .as("work done between the cycles survives the second snapshot")
                .isTrue();
        assertThat(usageKeys(executionId)).hasSize(3);
    }

    /** Runs the shared agent flow, which ends with the environment asleep. */
    protected void runFirstTurnAndSleep() throws Exception {
        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(),
                "Test Provider",
                ProviderType.OPENAI,
                "sk-mock-key-123",
                dockerAccessibleBaseUrl,
                List.of("gpt-4o"));
        executeAgentInstallationAndExecutionFlow(modelProvider);
        assertThat(environmentRepository
                        .findById(spawnedEnvironmentId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(EnvironmentStatus.SLEEPING);
        awaitUsageSegmentSealed(spawnedEnvironmentId());
    }

    /** The usage segment is sealed asynchronously after the sleep; a resume before that reuses the old key. */
    protected void awaitUsageSegmentSealed(UUID envId) {
        Awaitility.await("usage segment sealed after sleep")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> activeVirtualKeys(envId).isEmpty());
    }

    protected void resumeViaRest(UUID envId) throws Exception {
        mockMvc()
                .perform(post(
                                "/api/v1/teams/{teamId}/environments/{envId}/resume",
                                testContext.team().getId(),
                                envId)
                        .header("Authorization", "Bearer " + authToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    protected void awaitResumed(UUID envId, int acpInitializedBefore) {
        Awaitility.await("resumed environment connected")
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    ExecutionEnvironment env =
                            environmentRepository.findById(envId).orElseThrow();
                    assertThat(env.getStatus()).isEqualTo(EnvironmentStatus.CONNECTED);
                });
        Awaitility.await("agent session re-attached after resume")
                .atMost(Duration.ofSeconds(180))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> acpInitializedCount() > acpInitializedBefore);
    }

    protected SandboxExecution latestExecution(UUID envId) {
        return executionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(envId)
                .orElseThrow();
    }

    protected void runInSandbox(UUID executionId, String command) throws Exception {
        EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                spawnedEnvironmentId(), new EnvironmentRpcPayload.Exec(command, false, executionId.toString()));
        assertThat(result.status()).isEqualTo(ExecStatus.COMPLETED);
        assertThat(result.exitCode()).isZero();
    }

    private List<String> usageKeys(UUID executionId) {
        return transactionTemplate.execute(
                status -> executionRepository.findById(executionId).orElseThrow().getUsages().stream()
                        .map(SandboxExecutionUsage::getVirtualKey)
                        .toList());
    }

    private List<String> activeVirtualKeys(UUID envId) {
        return executionRepository.findActiveVirtualKeysByEnvironmentId(envId);
    }

    /** Adds a file through the connector (which triggers a diff push) and checks the diff lists only local files. */
    protected void assertDiffIsLocalOnly(UUID executionId, String newFile) throws Exception {
        runInSandbox(executionId, "echo added > " + WORKSPACE + "/" + newFile);
        awaitDiffSnapshotContaining(executionId, newFile);
        String summary = diffSummary(executionId);
        assertThat(summary).contains(AGENT_FILE).contains(newFile);
        for (String upstream : GitFixtureContainer.UPSTREAM_FILES) {
            assertThat(summary)
                    .as("upstream file %s must not be reported as a sandbox change after resume", upstream)
                    .doesNotContain(upstream);
        }
    }

    protected void awaitDiffSnapshotContaining(UUID executionId, String fileName) {
        Awaitility.await("diff snapshot lists " + fileName)
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(diffSummary(executionId)).contains(fileName));
    }

    protected String diffSummary(UUID executionId) {
        return diffSnapshotRepository
                .findByExecutionId(executionId)
                .map(ExecutionDiffSnapshot::getSummaryJson)
                .orElse("");
    }

    private static final String STATE_SCRIPT = "cd " + WORKSPACE + " && echo head=$(git rev-parse HEAD)"
            + " && echo remote=$(git remote get-url origin)"
            + " && echo session=$(cat /kratis/acp_session_id)"
            + " && echo base=$(cat /kratis/diff_base_branch)"
            + " && echo task=$(sha256sum " + AGENT_FILE + " | cut -d' ' -f1)";

    private Map<String, String> readSnapshotState(String snapshotImage) throws Exception {
        return parseState(
                dockerOutput("docker", "run", "--rm", "--entrypoint", "sh", snapshotImage, "-c", STATE_SCRIPT));
    }

    private Map<String, String> readLiveState() {
        var result = createDockerVerifier().executeInContainer("sh", "-c", STATE_SCRIPT);
        assertThat(result.exitCode()).as(new String(result.output())).isZero();
        return parseState(new String(result.output()));
    }

    private static Map<String, String> parseState(String output) {
        Map<String, String> state = new HashMap<>();
        for (String line : output.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                state.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return state;
    }

    private boolean inspectImage(String image) throws Exception {
        return suspendProcessExecutor
                        .execute(List.of("docker", "image", "inspect", image), null, null)
                        .exitCode()
                == 0;
    }

    protected String dockerOutput(String... command) throws Exception {
        ProcessExecutor.ProcessResult result = suspendProcessExecutor.execute(List.of(command), null, null);
        assertThat(result.exitCode())
                .as("%s: %s", String.join(" ", command), new String(result.output()))
                .isZero();
        return new String(result.output()).trim();
    }

    // ---- OpenCode scenario: identical to OpenCodeExecutionRealTest so the turn reaches IDLE ----

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.valueOf("OPENCODE");
    }

    @Override
    protected String getExpectedAgentName() {
        return "OpenCode";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.EDITED, ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Generate a brief title")
                .sseResponse(LlmResponseBuilders.openAiSseText("Write shell script"))
                .build());
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "write",
                                "{\"filePath\":\"/kratis/workspace/kratis_task.sh\",\"content\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\"}"))
                .maxMatches(1)
                .build());
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"tool\"")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "bash",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.openAiSseText("Task complete."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        return 3;
    }

    @Override
    int getExpectedHitlRequestCount() {
        return 2;
    }

    @Override
    protected boolean permissionRequestsCarryToolCallId() {
        return false;
    }

    @Override
    List<ExpectedFile> getExpectedFiles() {
        return List.of(ExpectedFile.withContent(WORKSPACE + "/" + AGENT_FILE, "Hello Kratis - what a lovely day"));
    }

    @Override
    protected String getExpectedToolOutputSubstring() {
        return "Hello Kratis - what a lovely day";
    }
}
