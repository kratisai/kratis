package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.api.restdto.UsageLogEntryDto;
import com.kratisai.controlplane.api.restdto.UsageSummaryDto;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
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
    private CanvasRepository canvasRepository;

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
    private TeamWikiBatchRepository teamWikiBatchRepository;

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

    private CanvasEntity specCanvas(java.util.UUID chatId, String documentId, String title) {
        CanvasEntity canvas = new CanvasEntity(chatId, documentId, title, "content");
        canvas.setCanvasType(CanvasType.SPEC);
        canvas.setNewRepoName("Test Repository");
        return canvas;
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
        CanvasEntity canvas = canvasRepository.save(specCanvas(chat.getId(), "short", "Test Job"));
        SandboxExecution exec = new SandboxExecution();
        exec.setEnvironment(env);
        exec.setCanvas(canvas);
        exec.setChat(chat);
        exec.setStatus(SandboxExecutionStatus.COMPLETED);
        exec.setStartedAt(Instant.now().minusSeconds(10));
        exec.setCompletedAt(Instant.now());
        exec.setModelName("gpt-4o");
        exec.setHarness(AgentHarness.valueOf("OPENCODE"));
        SandboxExecutionUsage execUsage = exec.startUsage("sk-exec-key", "gpt-4o");
        execUsage.setTotalTokens(100L);
        execUsage.setTotalSpend(0.005);
        sandboxExecutionRepository.save(exec);

        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = ingestionBatchRepository.save(new IngestionBatch(repo));
        batch.setStatus(IngestionStatus.FAILED);
        IngestionModelUsage usage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        usage.apply(new LlmUsageSnapshot(0.01, 500L, 400L, 100L));
        batch.getModelUsage().add(usage);
        ingestionBatchRepository.save(batch);

        ChatUsageSession chatSession = new ChatUsageSession(chat, "sk-test-virtual-key", "gpt-4o");
        chatSession.setStartedAt(Instant.now().minusSeconds(30));
        chatSession.getUsage().setTotalTokens(250L);
        chatSession.getUsage().setTotalSpend(0.003);
        chatUsageSessionRepository.save(chatSession);

        Page<UsageLogEntryDto> logs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, null, "gpt-4o", "OpenCode", PageRequest.of(0, 10));
        assertThat(logs).isNotNull();
        assertThat(logs.getContent()).hasSize(1);
        UsageLogEntryDto execEntry = logs.getContent().getFirst();
        assertThat(execEntry.targetUrl()).isEqualTo("/chats/" + chat.getId() + "/executions/" + exec.getId());

        Page<UsageLogEntryDto> ingestionLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, UsageType.INGESTION, null, null, PageRequest.of(0, 10));
        assertThat(ingestionLogs).isNotNull();
        assertThat(ingestionLogs.getContent()).hasSize(1);
        assertThat(ingestionLogs.getContent().getFirst().usageType()).isEqualTo(UsageType.INGESTION);
        assertThat(ingestionLogs.getContent().getFirst().targetUrl()).isEqualTo("/repos/" + repo.getId());

        Page<UsageLogEntryDto> chatLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, UsageType.CHAT, null, null, PageRequest.of(0, 10));
        assertThat(chatLogs).isNotNull();
        assertThat(chatLogs.getContent()).hasSize(1);
        UsageLogEntryDto chatEntry = chatLogs.getContent().getFirst();
        assertThat(chatEntry.activityTitle()).isEqualTo("Test Chat");
        assertThat(chatEntry.agentName()).isNull();
        assertThat(chatEntry.usageType()).isEqualTo(UsageType.CHAT);
        assertThat(chatEntry.status()).isEqualTo(SandboxExecutionStatus.RUNNING);
        assertThat(chatEntry.durationSeconds()).isNull();
        assertThat(chatEntry.modelIdentifiers()).containsExactly("gpt-4o");
        assertThat(chatEntry.totalTokens()).isEqualTo(250L);
        assertThat(chatEntry.totalSpend()).isEqualTo(0.003);
        assertThat(chatEntry.targetUrl()).isEqualTo("/chats/" + chat.getId());

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
                user.getId(), team.getId(), "24h", null, null, null, "unknown", null, PageRequest.of(0, 10));
        assertThat(byModel.getContent()).hasSize(1);
        assertThat(byModel.getContent().getFirst().modelIdentifiers()).containsExactly("unknown");

        // Filter by agent "SomeAgent" should exclude the chat session (since agentName is null)
        Page<UsageLogEntryDto> byAgent = usageService.getUsageLogs(
                user.getId(), team.getId(), "24h", null, null, null, null, "SomeAgent", PageRequest.of(0, 10));
        assertThat(byAgent.getContent()).isEmpty();

        // Custom timeframe
        Instant start = Instant.now().minusSeconds(100);
        Instant end = Instant.now().plusSeconds(100);
        Page<UsageLogEntryDto> customLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "custom", start, end, null, null, null, PageRequest.of(0, 10));
        assertThat(customLogs.getContent()).hasSize(1);
    }

    @Test
    void getUsageLogs_timeframeVariants_coverStartComputation() {
        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Test Chat"));
        ChatUsageSession session = new ChatUsageSession(chat, "sk-test-virtual-key", "gpt-4o");
        session.setStartedAt(Instant.now());
        chatUsageSessionRepository.save(session);

        assertThat(usageService
                        .getUsageLogs(
                                user.getId(),
                                team.getId(),
                                "last_24_hours",
                                null,
                                null,
                                null,
                                null,
                                null,
                                PageRequest.of(0, 10))
                        .getContent())
                .hasSize(1);
        assertThat(usageService
                        .getUsageLogs(
                                user.getId(),
                                team.getId(),
                                "last_7_days",
                                null,
                                null,
                                null,
                                null,
                                null,
                                PageRequest.of(0, 10))
                        .getContent())
                .hasSize(1);
        assertThat(usageService
                        .getUsageLogs(
                                user.getId(),
                                team.getId(),
                                "last_30_days",
                                null,
                                null,
                                null,
                                null,
                                null,
                                PageRequest.of(0, 10))
                        .getContent())
                .hasSize(1);
        assertThat(usageService
                        .getUsageLogs(
                                user.getId(),
                                team.getId(),
                                "bogus",
                                null,
                                null,
                                null,
                                null,
                                null,
                                PageRequest.of(0, 10))
                        .getContent())
                .hasSize(1);
        assertThat(usageService
                        .getUsageLogs(
                                user.getId(), team.getId(), null, null, null, null, null, null, PageRequest.of(0, 10))
                        .getContent())
                .hasSize(1);
    }

    @Test
    void getUsageLogs_executions_coverTitleDurationAndModelDefaults() {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setName("env-1");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setTeam(team);
        env.setUser(user);
        env = executionEnvironmentRepository.save(env);

        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Test Chat"));
        CanvasEntity longCanvas = canvasRepository.save(specCanvas(chat.getId(), "long", "x".repeat(100)));
        CanvasEntity shortCanvas = canvasRepository.save(specCanvas(chat.getId(), "short", "Test Job"));
        SandboxExecution longTitle = new SandboxExecution();
        longTitle.setEnvironment(env);
        longTitle.setCanvas(longCanvas);
        longTitle.setChat(chat);
        longTitle.setStatus(SandboxExecutionStatus.RUNNING);
        longTitle.setActiveSince(Instant.now().minusSeconds(100));
        longTitle.setStatus(SandboxExecutionStatus.COMPLETED);
        longTitle.setTaskPrompt("Sandbox preamble should not be the activity title");
        sandboxExecutionRepository.save(longTitle);

        SandboxExecution noModel = new SandboxExecution();
        noModel.setEnvironment(env);
        noModel.setCanvas(shortCanvas);
        noModel.setChat(chat);
        noModel.setStatus(SandboxExecutionStatus.COMPLETED);
        noModel.setStartedAt(Instant.now().minusSeconds(10));
        sandboxExecutionRepository.save(noModel);

        SandboxExecution otherHarness = new SandboxExecution();
        otherHarness.setEnvironment(env);
        otherHarness.setCanvas(shortCanvas);
        otherHarness.setChat(chat);
        otherHarness.setStatus(SandboxExecutionStatus.COMPLETED);
        otherHarness.setStartedAt(Instant.now().minusSeconds(10));
        otherHarness.setModelName("gpt-4o");
        otherHarness.setHarness(AgentHarness.valueOf("CLAUDE_CODE"));
        sandboxExecutionRepository.save(otherHarness);

        Page<UsageLogEntryDto> logs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, UsageType.EXECUTION, null, null, PageRequest.of(0, 10));
        assertThat(logs.getContent()).hasSize(3);
        assertThat(logs.getContent())
                .filteredOn(entry -> entry.activityTitle().endsWith("..."))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.activityTitle()).isEqualTo("x".repeat(60) + "...");
                    assertThat(entry.activityTitle()).hasSize(63);
                    assertThat(entry.durationSeconds()).isEqualTo(100L);
                    assertThat(entry.modelIdentifiers()).containsExactly("default");
                    assertThat(entry.agentName()).isEqualTo("OpenCode");
                });
        assertThat(logs.getContent())
                .filteredOn(entry -> entry.modelIdentifiers().contains("gpt-4o"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.agentName()).isEqualTo("Claude Code");
                    assertThat(entry.activityTitle()).isEqualTo("Test Job");
                });
        logs.getContent().forEach(entry -> assertThat(entry.targetUrl())
                .isEqualTo(
                        "/chats/" + chat.getId() + "/executions/" + entry.id().substring("exec-".length())));
    }

    @Test
    void getUsageLogs_executionTitle_prefersCanvasTitleOverTaskPrompt() {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setName("env-1");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setTeam(team);
        env.setUser(user);
        env = executionEnvironmentRepository.save(env);

        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Fix: Usage Log Execution Names"));
        CanvasEntity canvas =
                canvasRepository.save(specCanvas(chat.getId(), "execution-title", "Fix: Usage Log Execution Names"));
        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setCanvas(canvas);
        execution.setChat(chat);
        execution.setTaskPrompt("You are running as a non-root user inside a sandbox...");
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        execution.setStartedAt(Instant.now());
        sandboxExecutionRepository.save(execution);

        Page<UsageLogEntryDto> logs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, UsageType.EXECUTION, null, null, PageRequest.of(0, 10));

        UsageLogEntryDto execEntry = logs.getContent().getFirst();
        assertThat(execEntry.activityTitle()).isEqualTo("Fix: Usage Log Execution Names");
    }

    @Test
    void getUsageLogs_ingestionEntry_listsBothIngestionAndEmbeddingModels() {
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = ingestionBatchRepository.save(new IngestionBatch(repo));
        IngestionModelUsage chatUsage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        chatUsage.apply(new LlmUsageSnapshot(0.008, 400L, 300L, 100L));
        batch.getModelUsage().add(chatUsage);
        IngestionModelUsage embeddingUsage =
                new IngestionModelUsage(batch, ModelKind.EMBEDDING, "text-embedding-3-small", "team-embed-alias");
        embeddingUsage.apply(new LlmUsageSnapshot(0.002, 100L, 100L, 0L));
        batch.getModelUsage().add(embeddingUsage);
        ingestionBatchRepository.save(batch);

        Page<UsageLogEntryDto> ingestionLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "7d", null, null, UsageType.INGESTION, null, null, PageRequest.of(0, 10));
        assertThat(ingestionLogs.getContent()).hasSize(1);
        UsageLogEntryDto ingestionEntry = ingestionLogs.getContent().getFirst();
        assertThat(ingestionEntry.modelIdentifiers()).containsExactly("gpt-4o", "text-embedding-3-small");
        assertThat(ingestionEntry.totalTokens()).isEqualTo(500L);
        assertThat(ingestionEntry.totalSpend()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(0.000001));

        Page<UsageLogEntryDto> byEmbeddingModel = usageService.getUsageLogs(
                user.getId(),
                team.getId(),
                "7d",
                null,
                null,
                UsageType.INGESTION,
                "text-embedding-3-small",
                null,
                PageRequest.of(0, 10));
        assertThat(byEmbeddingModel.getContent()).hasSize(1);
    }

    @Test
    void getUsageSummary_ingestionPerModelBreakdown_usesPersistedSplit() {
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = ingestionBatchRepository.save(new IngestionBatch(repo));
        IngestionModelUsage chatUsage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        chatUsage.apply(new LlmUsageSnapshot(0.008, 400L, 300L, 100L));
        batch.getModelUsage().add(chatUsage);
        IngestionModelUsage embeddingUsage =
                new IngestionModelUsage(batch, ModelKind.EMBEDDING, "text-embedding-3-small", "team-embed-alias");
        embeddingUsage.apply(new LlmUsageSnapshot(0.002, 100L, 100L, 0L));
        batch.getModelUsage().add(embeddingUsage);
        ingestionBatchRepository.save(batch);

        UsageSummaryDto summary = usageService.getUsageSummary(user.getId(), team.getId(), "30d", null, null);
        assertThat(summary.totalOperations()).isEqualTo(1);
        assertThat(summary.totalCost()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(summary.modelShare())
                .extracting(UsageSummaryDto.ShareBreakdownDto::label)
                .containsExactlyInAnyOrder("gpt-4o", "text-embedding-3-small");
        assertThat(summary.modelShare())
                .filteredOn(share -> share.label().equals("gpt-4o"))
                .singleElement()
                .satisfies(share -> {
                    assertThat(share.cost()).isCloseTo(0.008, org.assertj.core.data.Offset.offset(0.000001));
                    assertThat(share.tokens()).isEqualTo(400L);
                });
        assertThat(summary.modelShare())
                .filteredOn(share -> share.label().equals("text-embedding-3-small"))
                .singleElement()
                .satisfies(share -> {
                    assertThat(share.cost()).isCloseTo(0.002, org.assertj.core.data.Offset.offset(0.000001));
                    assertThat(share.tokens()).isEqualTo(100L);
                });
        assertThat(summary.modelShare().stream()
                        .mapToDouble(UsageSummaryDto.ShareBreakdownDto::cost)
                        .sum())
                .isCloseTo(0.01, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void getUsageSummary_batchStartedBeforeWindow_excludedFromShare() {
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch stale = new IngestionBatch(repo);
        stale.setStartedAt(Instant.parse("2020-01-01T00:00:00Z"));
        IngestionModelUsage staleUsage = new IngestionModelUsage(stale, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        staleUsage.apply(new LlmUsageSnapshot(0.5, 5000L, 4000L, 1000L));
        stale.getModelUsage().add(staleUsage);
        ingestionBatchRepository.save(stale);

        UsageSummaryDto summary = usageService.getUsageSummary(user.getId(), team.getId(), "24h", null, null);
        assertThat(summary.totalOperations()).isZero();
        assertThat(summary.modelShare()).isEmpty();
    }

    @Test
    void getUsageSummary_mixedOperations_aggregatesTotalsAndModelShare() {
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = ingestionBatchRepository.save(new IngestionBatch(repo));
        IngestionModelUsage chatUsage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        chatUsage.apply(new LlmUsageSnapshot(0.008, 400L, 300L, 100L));
        batch.getModelUsage().add(chatUsage);
        IngestionModelUsage embeddingUsage =
                new IngestionModelUsage(batch, ModelKind.EMBEDDING, "text-embedding-3-small", "team-embed-alias");
        embeddingUsage.apply(new LlmUsageSnapshot(0.002, 100L, 100L, 0L));
        batch.getModelUsage().add(embeddingUsage);
        ingestionBatchRepository.save(batch);

        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Test Chat"));
        ChatUsageSession chatSession = new ChatUsageSession(chat, "sk-test-virtual-key", "gpt-4o");
        chatSession.setStartedAt(Instant.now());
        chatSession.getUsage().setTotalTokens(250L);
        chatSession.getUsage().setTotalSpend(0.003);
        chatUsageSessionRepository.save(chatSession);

        UsageSummaryDto summary = usageService.getUsageSummary(user.getId(), team.getId(), "30d", null, null);
        assertThat(summary.totalOperations()).isEqualTo(2);
        assertThat(summary.totalTokens()).isEqualTo(750L);
        assertThat(summary.totalCost()).isCloseTo(0.013, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(summary.modelShare())
                .filteredOn(share -> share.label().equals("gpt-4o"))
                .singleElement()
                .satisfies(share -> {
                    assertThat(share.cost()).isCloseTo(0.011, org.assertj.core.data.Offset.offset(0.0001));
                    assertThat(share.tokens()).isEqualTo(650L);
                    assertThat(share.count()).isEqualTo(2L);
                });
        assertThat(summary.modelShare())
                .filteredOn(share -> share.label().equals("text-embedding-3-small"))
                .singleElement()
                .satisfies(share -> assertThat(share.count()).isEqualTo(1L));
        assertThat(summary.timeSeries()).isNotEmpty();
        assertThat(summary.modelShare().stream()
                        .mapToDouble(UsageSummaryDto.ShareBreakdownDto::cost)
                        .sum())
                .isCloseTo(0.013, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void getUsageSummary_nullTotalsAndZeroCost_coverShareEdgeCases() {
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = ingestionBatchRepository.save(new IngestionBatch(repo));
        IngestionModelUsage nullTotals = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        batch.getModelUsage().add(nullTotals);
        ingestionBatchRepository.save(batch);

        UsageSummaryDto summary = usageService.getUsageSummary(user.getId(), team.getId(), "30d", null, null);
        assertThat(summary.totalOperations()).isEqualTo(1);
        assertThat(summary.totalCost()).isZero();
        assertThat(summary.totalTokens()).isZero();
        assertThat(summary.modelShare()).singleElement().satisfies(share -> {
            assertThat(share.label()).isEqualTo("gpt-4o");
            assertThat(share.cost()).isZero();
            assertThat(share.tokens()).isZero();
            assertThat(share.count()).isEqualTo(1L);
            assertThat(share.percentage()).isZero();
        });
    }

    @Test
    void getUsageSummary_notMember_throwsForbidden() {
        User otherUser = userRepository.save(new User("other@kratis.ai", "hash", "Other User"));
        assertThatThrownBy(() -> usageService.getUsageSummary(otherUser.getId(), team.getId(), "24h", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getUsageLogs_usageTypeFilter_separatesAllFourProducers() {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setName("env-1");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setTeam(team);
        env.setUser(user);
        env = executionEnvironmentRepository.save(env);

        SandboxExecution exec = new SandboxExecution();
        exec.setEnvironment(env);
        exec.setChat(chatRepository.save(new ChatEntity(team, user, "Exec Chat")));
        exec.setCanvas(canvasRepository.save(specCanvas(exec.getChat().getId(), "exec-canvas", "Exec Chat")));
        exec.setStatus(SandboxExecutionStatus.COMPLETED);
        exec.setStartedAt(Instant.now().minusSeconds(10));
        exec.setModelName("gpt-4o");
        sandboxExecutionRepository.save(exec);

        TeamWikiBatch twBatch = new TeamWikiBatch(team, TeamWikiBuildMode.FULL, TeamWikiTriggerKind.MANUAL, null);
        twBatch.setStatus(IngestionStatus.SUCCESS);
        twBatch.setStartedAt(Instant.now().minusSeconds(30));
        twBatch.setCompletedAt(Instant.now());
        teamWikiBatchRepository.save(twBatch);

        ChatUsageSession openSession =
                new ChatUsageSession(chatRepository.save(new ChatEntity(team, user, "Open Chat")), "sk-open", "gpt-4o");
        openSession.setStartedAt(Instant.now().minusSeconds(60));
        chatUsageSessionRepository.save(openSession);

        ChatUsageSession closedSession = new ChatUsageSession(
                chatRepository.save(new ChatEntity(team, user, "Closed Chat")), "sk-closed", "gpt-4o");
        closedSession.setStartedAt(Instant.now().minusSeconds(120));
        closedSession.setEndedAt(Instant.now().minusSeconds(10));
        chatUsageSessionRepository.save(closedSession);

        Page<UsageLogEntryDto> execLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "30d", null, null, UsageType.EXECUTION, null, null, PageRequest.of(0, 10));
        assertThat(execLogs.getContent()).hasSize(1);
        assertThat(execLogs.getContent().getFirst().usageType()).isEqualTo(UsageType.EXECUTION);

        Page<UsageLogEntryDto> chatLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "30d", null, null, UsageType.CHAT, null, null, PageRequest.of(0, 10));
        assertThat(chatLogs.getContent()).hasSize(2);
        assertThat(chatLogs.getContent())
                .allSatisfy(entry -> assertThat(entry.usageType()).isEqualTo(UsageType.CHAT));
        assertThat(chatLogs.getContent())
                .extracting(UsageLogEntryDto::status)
                .containsExactlyInAnyOrder(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.COMPLETED);

        Page<UsageLogEntryDto> teamWikiLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "30d", null, null, UsageType.TEAM_WIKI, null, null, PageRequest.of(0, 10));
        assertThat(teamWikiLogs.getContent()).hasSize(1);
        assertThat(teamWikiLogs.getContent().getFirst().usageType()).isEqualTo(UsageType.TEAM_WIKI);

        Page<UsageLogEntryDto> ingestionLogs = usageService.getUsageLogs(
                user.getId(), team.getId(), "30d", null, null, UsageType.INGESTION, null, null, PageRequest.of(0, 10));
        assertThat(ingestionLogs.getContent()).isEmpty();
    }
}
