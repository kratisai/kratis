package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.ReActLoop.ToolPhase;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBatchLog;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiModelUsage;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RelationEntry;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RepoEntry;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.IngestionModelUsageRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.repository.TeamWikiModelUsageRepository;
import com.kratisai.controlplane.service.EmbeddingModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.UsageService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

class TeamWikiCoverageTest {

    @Test
    void entities_gettersSettersAndHelpersWork() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        TeamWikiBatch batch = new TeamWikiBatch();
        UUID id = UUID.randomUUID();
        batch.setId(id);
        batch.setTeam(team);
        batch.setStatus(IngestionStatus.QUEUED);
        batch.setBuildMode(TeamWikiBuildMode.FULL);
        batch.setTriggerKind(TeamWikiTriggerKind.requireNonNull(TeamWikiTriggerKind.MANUAL));
        batch.setStartedAt(Instant.now());
        batch.setCompletedAt(Instant.now());
        batch.setErrorMessage("none");
        batch.setTotalToolCalls(3L);
        batch.setLogs(List.of());
        batch.setWikiPages(List.of());
        batch.setEmbeddings(List.of());

        assertThat(batch.getId()).isEqualTo(id);
        assertThat(batch.getBuildMode()).isEqualTo(TeamWikiBuildMode.FULL);
        assertThat(batch.getTriggerKind()).isEqualTo(TeamWikiTriggerKind.MANUAL);
        assertThat(batch.getErrorMessage()).isEqualTo("none");
        assertThat(batch.getTotalToolCalls()).isEqualTo(3L);
        assertThat(batch.getLogs()).isEmpty();
        assertThat(batch.getWikiPages()).isEmpty();
        assertThat(batch.getEmbeddings()).isEmpty();

        TeamWikiBatchLog log = new TeamWikiBatchLog(batch, team.getId(), "INFO", "START", "hello");
        assertThat(log.getId()).isNull();
        assertThat(log.getTeamWikiBatch()).isEqualTo(batch);
        assertThat(log.getTeamId()).isEqualTo(team.getId());
        assertThat(log.getLevel()).isEqualTo("INFO");
        assertThat(log.getStep()).isEqualTo("START");
        assertThat(log.getMessage()).isEqualTo("hello");
        assertThat(log.getCreatedAt()).isNull();

        TeamWikiModelUsage usage = new TeamWikiModelUsage(batch, ModelKind.CHAT, "gpt-4o", "openai/gpt-4o");
        usage.setTotalSpend(1.5);
        usage.setTotalTokens(100L);
        usage.apply(new LlmUsageSnapshot(2.0, 200L, 120L, 80L));
        assertThat(usage.getId()).isNull();
        assertThat(usage.getTeamWikiBatch()).isEqualTo(batch);
        assertThat(usage.getModelKind()).isEqualTo(ModelKind.CHAT);
        assertThat(usage.getModelIdentifier()).isEqualTo("gpt-4o");
        assertThat(usage.getLitellmAlias()).isEqualTo("openai/gpt-4o");
        assertThat(usage.getVirtualKey()).isNull();
        assertThat(usage.getTotalSpend()).isEqualTo(2.0);
        assertThat(usage.getTotalTokens()).isEqualTo(200L);
        assertThat(usage.getPromptTokens()).isEqualTo(120L);
        assertThat(usage.getCompletionTokens()).isEqualTo(80L);
        assertThat(usage.getUsageLastUpdatedAt()).isNotNull();

        IngestionProperties.TeamWiki twProps = new IngestionProperties.TeamWiki();
        twProps.setEnabled(false);
        twProps.setMaxDeferral(Duration.ofMinutes(45));
        twProps.setMaxBuildsPer24h(2);
        twProps.setPollInterval(Duration.ofSeconds(15));
        twProps.setFailureBackoff(Duration.ofMinutes(10));
        twProps.setMaxIterations(20);
        assertThat(twProps.isEnabled()).isFalse();
        assertThat(twProps.getMaxDeferral()).isEqualTo(Duration.ofMinutes(45));
        assertThat(twProps.getMaxBuildsPer24h()).isEqualTo(2);
        assertThat(twProps.getPollInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(twProps.getFailureBackoff()).isEqualTo(Duration.ofMinutes(10));
        assertThat(twProps.getMaxIterations()).isEqualTo(20);
    }

    @Test
    void renderRelation_coversAllNaturesAndDirections() {
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.CALLS, Direction.A_TO_B))
                .isEqualTo("b calls a");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.CALLS, Direction.B_TO_A))
                .isEqualTo("a calls b");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.CALLS, Direction.UNKNOWN))
                .contains("direction unconfirmed");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.BUILDS_AGAINST, Direction.A_TO_B))
                .isEqualTo("b builds against a");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.BUILDS_AGAINST, Direction.B_TO_A))
                .isEqualTo("a builds against b");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.BUILDS_AGAINST, Direction.BIDIRECTIONAL))
                .contains("direction unconfirmed");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.HOSTS, Direction.A_TO_B))
                .isEqualTo("a hosts b");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.HOSTS, Direction.B_TO_A))
                .isEqualTo("b hosts a");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.HOSTS, Direction.UNKNOWN))
                .contains("direction unconfirmed");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.MESSAGES, Direction.BIDIRECTIONAL))
                .isEqualTo("a exchanges messages with b");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.SHARES_STATE, Direction.BIDIRECTIONAL))
                .isEqualTo("a shares persistent state with b");
        assertThat(TeamWikiSnapshotService.renderRelation("a", "b", Nature.PEER, Direction.BIDIRECTIONAL))
                .isEqualTo("a collaborates as peer with b");
    }

    @Test
    void semanticIndexingService_indexTeamWikiBatch_embedsAndSavesPages() {
        CtxWikiPageRepository pageRepo = mock(CtxWikiPageRepository.class);
        CtxEmbeddingRepository embRepo = mock(CtxEmbeddingRepository.class);
        EmbeddingModelFactory embFactory = mock(EmbeddingModelFactory.class);
        LiteLLMProvisioningService litellm = mock(LiteLLMProvisioningService.class);
        EmbeddingModel embModel = mock(EmbeddingModel.class);

        SemanticIndexingService service = new SemanticIndexingService(pageRepo, embRepo, embFactory, litellm);

        Team team = new Team();
        team.setId(UUID.randomUUID());
        ModelProvider provider = new ModelProvider();
        provider.setId(UUID.randomUUID());
        team.setEmbeddingProvider(provider);
        team.setEmbeddingModel("text-embedding-3-small");

        TeamWikiBatch batch = new TeamWikiBatch();
        batch.setId(UUID.randomUUID());
        batch.setTeam(team);
        TeamWikiModelUsage embUsage = new TeamWikiModelUsage(
                batch, ModelKind.EMBEDDING, "text-embedding-3-small", "openai/text-embedding-3-small");
        embUsage.setVirtualKey("sk-emb");
        batch.getModelUsage().add(embUsage);

        CtxWikiPage page = new CtxWikiPage(batch, team.getId(), null, "overview", "Overview", 0, "Team wiki content");
        when(pageRepo.findByTeamWikiBatchId(batch.getId())).thenReturn(List.of(page));
        when(litellm.buildLiteLLMModelName(provider, "text-embedding-3-small"))
                .thenReturn("openai/text-embedding-3-small");
        when(embFactory.createEmbeddingModelViaLiteLLM(provider, "openai/text-embedding-3-small", "sk-emb"))
                .thenReturn(embModel);
        when(embModel.embed(any(String.class))).thenReturn(new float[] {0.1f, 0.2f});

        service.indexTeamWikiBatch(batch, team);
        verify(embRepo).saveAll(any());
    }

    @Test
    void usageService_includesTeamWikiBatchInLogsAndSummary() {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);

        TeamMemberRepository memberRepo = mock(TeamMemberRepository.class);
        SandboxExecutionRepository execRepo = mock(SandboxExecutionRepository.class);
        IngestionBatchRepository ingestRepo = mock(IngestionBatchRepository.class);
        ChatRepository chatRepo = mock(ChatRepository.class);
        ChatUsageSessionRepository chatUsageRepo = mock(ChatUsageSessionRepository.class);
        IngestionModelUsageRepository ingestModelRepo = mock(IngestionModelUsageRepository.class);
        TeamWikiBatchRepository twBatchRepo = mock(TeamWikiBatchRepository.class);
        TeamWikiModelUsageRepository twModelRepo = mock(TeamWikiModelUsageRepository.class);

        TeamWikiBatch twBatch = new TeamWikiBatch();
        twBatch.setId(UUID.randomUUID());
        twBatch.setTeam(team);
        twBatch.setBuildMode(TeamWikiBuildMode.FULL);
        twBatch.setStatus(IngestionStatus.SUCCESS);
        twBatch.setStartedAt(Instant.now().minusSeconds(60));
        twBatch.setCompletedAt(Instant.now());
        TeamWikiModelUsage mu = new TeamWikiModelUsage(twBatch, ModelKind.CHAT, "gpt-4o", "openai/gpt-4o");
        mu.setTotalSpend(0.25);
        mu.setTotalTokens(500L);
        twBatch.setModelUsage(List.of(mu));

        when(memberRepo.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
        when(twBatchRepo.findByTeamIdAndStartedAtBetween(any(), any(), any())).thenReturn(List.of(twBatch));
        when(twModelRepo.findByTeamWikiBatchId(twBatch.getId())).thenReturn(List.of(mu));

        UsageService usageService = new UsageService(
                memberRepo, execRepo, ingestRepo, chatRepo, chatUsageRepo, ingestModelRepo, twBatchRepo, twModelRepo);

        var logs =
                usageService.getUsageLogs(userId, teamId, "7d", null, null, "all", null, null, PageRequest.of(0, 10));
        assertThat(logs.getContent()).hasSize(1);
        assertThat(logs.getContent().getFirst().activityTitle()).contains("Team Wiki Build");
        assertThat(logs.getContent().getFirst().targetUrl()).isEqualTo("/wiki");

        var summary = usageService.getUsageSummary(userId, teamId, "7d", null, null);
        assertThat(summary.totalCost()).isEqualTo(0.25);
    }

    @Test
    void contextAndLogBounding_coverEdgeCases() {
        assertThatThrownBy(() -> TeamWikiContext.requireBatchId(null, "test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TeamWikiContext.requireBatchId(new ToolContext(Map.of()), "test"))
                .isInstanceOf(IllegalStateException.class);
        UUID id = UUID.randomUUID();
        assertThat(TeamWikiContext.requireBatchId(
                        new ToolContext(Map.of(TeamWikiContext.BATCH_ID_KEY, id.toString())), "test"))
                .isEqualTo(id);
        assertThat(TeamWikiContext.repoBatchIdsByName(null)).isEmpty();
        assertThat(TeamWikiContext.repoBatchIdsByName(new ToolContext(Map.of())))
                .isEmpty();

        assertThat(TeamWikiBatchLogService.boundMessage(null)).isEmpty();
        assertThat(TeamWikiBatchLogService.boundMessage("short")).isEqualTo("short");
        String huge = "x".repeat(TeamWikiBatchLogService.MAX_MESSAGE_CHARS + 100);
        assertThat(TeamWikiBatchLogService.boundMessage(huge))
                .hasSize(TeamWikiBatchLogService.MAX_MESSAGE_CHARS)
                .endsWith("... [truncated]");
    }

    @Test
    void buildDeltaSection_rendersAllDiffCategories() {
        var snapshotService = new TeamWikiSnapshotService(null, null, null);
        var genService = new TeamWikiGenerationService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                snapshotService,
                new ListTeamWikiPagesTool(null),
                new ReadTeamWikiPageTool(null),
                new WriteTeamWikiPageTool(null, null, null, null),
                new DeleteTeamWikiPageTool(null),
                new TeamWikiRepoReadTools(null, null),
                Runnable::run,
                mock(PlatformTransactionManager.class),
                new IngestionProperties());

        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        var prev = new TeamWikiSourceSnapshot(
                List.of(
                        new RepoEntry(r1, "repo-1", UUID.randomUUID(), "c1", RepoRole.SERVICE),
                        new RepoEntry(r2, "repo-2", UUID.randomUUID(), "c2", RepoRole.LIBRARY)),
                List.of(new RelationEntry(
                        r1, "repo-1", r2, "repo-2", Nature.BUILDS_AGAINST, Direction.B_TO_A, Ceiling.CONFIRMED)));
        var curr = new TeamWikiSourceSnapshot(
                List.of(
                        new RepoEntry(r1, "repo-1", UUID.randomUUID(), "c1-new", RepoRole.INFRA),
                        new RepoEntry(r3, "repo-3", UUID.randomUUID(), "c3", RepoRole.SERVICE)),
                List.of(new RelationEntry(
                        r1, "repo-1", r3, "repo-3", Nature.CALLS, Direction.A_TO_B, Ceiling.CONFIRMED)));

        String delta = genService.buildDeltaSection(prev, curr);
        assertThat(delta)
                .contains("Added repositories")
                .contains("Removed repositories")
                .contains("Role changes")
                .contains("Added relations")
                .contains("Removed relations")
                .contains("Re-ingested repositories");

        var logService = mock(TeamWikiBatchLogService.class);
        var batchLogger = new TeamWikiBatchLogService.BatchLogger(UUID.randomUUID(), UUID.randomUUID(), logService);
        batchLogger.warn("STEP", "warn");
        batchLogger.error("STEP", "err");
        genService.logToolEvent(batchLogger, "write_team_wiki_page", ToolPhase.START, "slug");
        genService.logToolEvent(batchLogger, "write_team_wiki_page", ToolPhase.COMPLETE, "");
        genService.logToolEvent(batchLogger, "write_team_wiki_page", ToolPhase.ERROR, "bad");

        CtxWikiPage p = new CtxWikiPage();
        p.setTitle("Overview");
        p.setContent("Covers repo-1 only");
        assertThat(TeamWikiGenerationService.findUncoveredRepos(curr.repos(), List.of(p)))
                .containsExactly("repo-3");
    }
}
