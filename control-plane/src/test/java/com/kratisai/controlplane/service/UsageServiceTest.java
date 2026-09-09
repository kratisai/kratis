package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.UsageLogEntryDto;
import com.kratisai.controlplane.api.restdto.UsageSummaryDto;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

@SpringIntegrationTest
class UsageServiceTest {

    @Autowired
    private UsageService usageService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatUsageSessionRepository chatUsageSessionRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;
    private User user;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        var context = testDataFactory.createUserAndTeam();
        team = context.team();
        user = context.user();
    }

    @Test
    void getUsageLogs_andSummary_withExecutionsAndIngestions() {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setName("env-1");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setTeam(team);
        env.setUser(user);
        env = executionEnvironmentRepository.save(env);

        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Test Chat"));
        SandboxExecution exec = new SandboxExecution();
        exec.setEnvironment(env);
        exec.setChat(chat);
        exec.setStatus(SandboxExecutionStatus.COMPLETED);
        exec.setStartedAt(Instant.now().minusSeconds(10));
        exec.setCompletedAt(Instant.now());
        exec.setModelName("gpt-4o");
        exec.setHarness(AgentHarness.OPENCODE);
        exec.setTotalTokens(100L);
        exec.setTotalSpend(0.005);
        sandboxExecutionRepository.save(exec);

        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = ingestionBatchRepository.save(new IngestionBatch(repo));
        batch.setStatus(IngestionStatus.FAILED);
        batch.getUsage().setTotalTokens(500L);
        batch.getUsage().setTotalSpend(0.01);
        ingestionBatchRepository.save(batch);

        ChatUsageSession chatSession = new ChatUsageSession(chat, "sk-test-virtual-key", "gpt-4o");
        chatSession.setStartedAt(Instant.now().minusSeconds(30));
        chatSession.getUsage().setTotalTokens(250L);
        chatSession.getUsage().setTotalSpend(0.003);
        chatUsageSessionRepository.save(chatSession);

        Page<UsageLogEntryDto> logs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, "all", "gpt-4o", "OpenCode", PageRequest.of(0, 10));
        assertThat(logs).isNotNull();

        Page<UsageLogEntryDto> ingestionLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, "INGESTION", null, null, PageRequest.of(0, 10));
        assertThat(ingestionLogs).isNotNull();

        Page<UsageLogEntryDto> chatLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, "CHAT", null, null, PageRequest.of(0, 10));
        assertThat(chatLogs).isNotNull();
        assertThat(chatLogs.getContent()).hasSize(1);
        UsageLogEntryDto chatEntry = chatLogs.getContent().getFirst();
        assertThat(chatEntry.activityTitle()).isEqualTo("Test Chat");
        assertThat(chatEntry.agentName()).isNull();
        assertThat(chatEntry.status()).isNull();
        assertThat(chatEntry.durationSeconds()).isNull();
        assertThat(chatEntry.modelIdentifier()).isEqualTo("gpt-4o");
        assertThat(chatEntry.totalTokens()).isEqualTo(250L);
        assertThat(chatEntry.totalSpend()).isEqualTo(0.003);

        UsageSummaryDto summary = usageService.getUsageSummary(user.getId(), team.getId(), "30d", null, null);
        assertThat(summary).isNotNull();
        assertThat(summary.totalOperations()).isEqualTo(3);
        assertThat(summary.totalTokens()).isEqualTo(100L + 500L + 250L);
        assertThat(summary.totalCost()).isCloseTo(0.005 + 0.01 + 0.003, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(summary.modelShare()).isNotEmpty();
        assertThat(summary.agentShareByCost()).isNotEmpty();
        assertThat(summary.agentShareByTime()).isNotEmpty();
    }

    @Test
    void getUsageLogs_filteringByModelAndAgent() {
        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Chat Without Agent"));
        ChatUsageSession session = new ChatUsageSession(chat, "sk-test", null);
        session.setStartedAt(Instant.now());
        session.getUsage().setTotalTokens(50L);
        session.getUsage().setTotalSpend(0.001);
        chatUsageSessionRepository.save(session);

        // Filter by model "unknown"
        Page<UsageLogEntryDto> byModel = usageService.getUsageLogs(
                user.getId(), team.getId(), "24h", null, null, "all", "unknown", null, PageRequest.of(0, 10));
        assertThat(byModel.getContent()).hasSize(1);
        assertThat(byModel.getContent().getFirst().modelIdentifier()).isEqualTo("unknown");

        // Filter by agent "SomeAgent" should exclude the chat session (since agentName is null)
        Page<UsageLogEntryDto> byAgent = usageService.getUsageLogs(
                user.getId(), team.getId(), "24h", null, null, "all", null, "SomeAgent", PageRequest.of(0, 10));
        assertThat(byAgent.getContent()).isEmpty();

        // Custom timeframe
        Instant start = Instant.now().minusSeconds(100);
        Instant end = Instant.now().plusSeconds(100);
        Page<UsageLogEntryDto> customLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "custom", start, end, "all", null, null, PageRequest.of(0, 10));
        assertThat(customLogs.getContent()).hasSize(1);
    }

    @Test
    void getUsageSummary_notMember_throwsForbidden() {
        User otherUser = userRepository.save(new User("other@kratis.ai", "hash", "Other User"));
        assertThatThrownBy(() -> usageService.getUsageSummary(otherUser.getId(), team.getId(), "24h", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
