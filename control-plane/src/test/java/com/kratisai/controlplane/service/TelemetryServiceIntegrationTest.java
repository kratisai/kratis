package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.kratisai.controlplane.client.PostHogCaptureRequest;
import com.kratisai.controlplane.config.TelemetryProperties;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.FakePostHogClient;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

@SpringIntegrationTest
class TelemetryServiceIntegrationTest {

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private FakePostHogClient fakePostHogClient;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatUsageSessionRepository chatUsageSessionRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private CanvasRepository canvasRepository;

    @Autowired
    private KratisInstallationRepository installationRepository;

    @Autowired
    private TelemetryProperties telemetryProperties;

    private String previousDisabled;
    private String previousVersion;
    private String previousBuildTag;

    @BeforeEach
    void setUp() {
        previousDisabled = telemetryProperties.getDisabled();
        previousVersion = telemetryProperties.getVersion();
        previousBuildTag = telemetryProperties.getBuildTag();
        telemetryProperties.setDisabled("false");
        telemetryProperties.setVersion("1.2.3-test");
        telemetryProperties.setBuildTag("sha-abc123");
        databaseCleaner.cleanAll();
        fakePostHogClient.reset();
    }

    @AfterEach
    void tearDown() {
        fakePostHogClient.reset();
        telemetryProperties.setDisabled(previousDisabled);
        telemetryProperties.setVersion(previousVersion);
        telemetryProperties.setBuildTag(previousBuildTag);
        databaseCleaner.cleanAll();
    }

    @Test
    void reportUsageIsScheduled() throws Exception {
        var method = TelemetryService.class.getMethod("reportUsage");
        assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
    }

    @Test
    void reportUsagePostsCumulativeStatsAndReusesInstallationId() {
        seedOneOfEach();

        telemetryService.reportUsage();
        telemetryService.reportUsage();

        assertThat(fakePostHogClient.requests()).hasSize(2);
        PostHogCaptureRequest first = fakePostHogClient.requests().getFirst();
        PostHogCaptureRequest second = fakePostHogClient.requests().get(1);

        assertThat(first.event()).isEqualTo("kratis_daily_usage");
        assertThat(second.distinctId()).isEqualTo(first.distinctId());

        Map<String, Object> props = first.properties();
        assertThat(props).containsEntry("version", "1.2.3-test");
        assertThat(props).containsEntry("build_tag", "sha-abc123");
        assertThat(props).containsEntry("team_count", 1L);
        assertThat(props).containsEntry("repo_count", 1L);
        assertThat(props).containsEntry("planning_topic_count", 1L);
        assertThat(props).containsEntry("planning_cost", 1.5);
        assertThat(props).containsEntry("ingestion_job_count", 1L);
        assertThat(props).containsEntry("ingestion_cost", 2.25);
        assertThat(props).containsEntry("execution_count", 1L);
        assertThat(props).containsEntry("execution_cost", 0.125);

        assertThat(installationRepository.count()).isEqualTo(1);
        KratisInstallation installation = installationRepository.findAll().getFirst();
        assertThat(installation.getInstallId().toString()).isEqualTo(first.distinctId());
    }

    @Test
    void reportUsageSwallowsPostHogFailures() {
        fakePostHogClient.setShouldThrow(true);

        assertThatCode(telemetryService::reportUsage).doesNotThrowAnyException();
    }

    private void seedOneOfEach() {
        TestDataFactory.AuthContext context = testDataFactory.createAuthenticatedContext();
        Team team = context.team();
        User user = context.user();

        Repository repository = new Repository(
                "telemetry-repo", "https://example.com/telemetry-repo.git", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        repositoryRepository.save(repository);

        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Telemetry topic"));
        CanvasEntity canvas = new CanvasEntity(chat.getId(), "telemetry-spec", "Test Spec", "content");
        canvas.setCanvasType(CanvasType.SPEC);
        canvas.setNewRepoName("Test Repository");
        canvasRepository.save(canvas);

        ChatUsageSession chatSession = new ChatUsageSession(chat, "sk-telemetry-virtual-key", "gpt-4o");
        chatSession.getUsage().setTotalSpend(1.5);
        chatUsageSessionRepository.save(chatSession);

        IngestionBatch batch = new IngestionBatch(repository);
        batch.setStatus(IngestionStatus.SUCCESS);
        IngestionModelUsage usage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        usage.apply(new LlmUsageSnapshot(2.25, 1000L, 800L, 200L));
        batch.getModelUsage().add(usage);
        ingestionBatchRepository.save(batch);

        SandboxExecution execution = new SandboxExecution();
        execution.setCanvas(canvas);
        execution.setEnvironment(context.defaultSandbox());
        execution.setChat(chat);
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        execution.setModelName("gpt-4o");
        execution.setHarness(AgentHarness.valueOf("OPENCODE"));
        execution.startUsage("sk-telemetry-key", "gpt-4o").setTotalSpend(0.125);
        sandboxExecutionRepository.save(execution);
    }
}
