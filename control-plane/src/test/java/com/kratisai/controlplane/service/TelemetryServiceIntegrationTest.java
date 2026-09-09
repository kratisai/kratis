package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.client.PostHogCaptureRequest;
import com.kratisai.controlplane.client.PostHogClient;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;

@SpringIntegrationTest
@Import(TelemetryServiceIntegrationTest.FakePostHogClientConfig.class)
@TestPropertySource(
        properties = {
            "kratis.telemetry.disabled=false",
            "kratis.telemetry.version=1.2.3-test",
            "kratis.telemetry.build-tag=sha-abc123"
        })
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
    private KratisInstallationRepository installationRepository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        fakePostHogClient.reset();
    }

    @AfterEach
    void tearDown() {
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

        ChatUsageSession chatSession = new ChatUsageSession(chat, "sk-telemetry-virtual-key", "gpt-4o");
        chatSession.getUsage().setTotalSpend(1.5);
        chatUsageSessionRepository.save(chatSession);

        IngestionBatch batch = new IngestionBatch(repository);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.getUsage().setTotalSpend(2.25);
        ingestionBatchRepository.save(batch);

        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(context.defaultSandbox());
        execution.setChat(chat);
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        execution.setModelName("gpt-4o");
        execution.setHarness(AgentHarness.OPENCODE);
        execution.getUsage().setTotalSpend(0.125);
        sandboxExecutionRepository.save(execution);
    }

    public static class FakePostHogClient implements PostHogClient {

        private final List<PostHogCaptureRequest> requests = new CopyOnWriteArrayList<>();
        private volatile boolean shouldThrow;

        void setShouldThrow(boolean shouldThrow) {
            this.shouldThrow = shouldThrow;
        }

        void reset() {
            requests.clear();
            shouldThrow = false;
        }

        List<PostHogCaptureRequest> requests() {
            return requests;
        }

        @Override
        public void capture(PostHogCaptureRequest request) {
            if (shouldThrow) {
                throw new IllegalStateException("Simulated PostHog failure");
            }
            requests.add(request);
        }
    }

    @TestConfiguration
    static class FakePostHogClientConfig {

        @Bean
        @Primary
        FakePostHogClient fakePostHogClient() {
            return new FakePostHogClient();
        }
    }
}
