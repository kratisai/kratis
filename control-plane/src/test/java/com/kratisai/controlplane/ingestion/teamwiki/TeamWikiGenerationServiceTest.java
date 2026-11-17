package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class TeamWikiGenerationServiceTest {

    @Autowired
    private TeamWikiGenerationService generationService;

    @Autowired
    private TeamWikiSnapshotService snapshotService;

    @Autowired
    private TeamWikiBatchLogService batchLogService;

    @Autowired
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxWikiPageRepository wikiPageRepository;

    @Autowired
    private CtxRepoSynopsisRepository synopsisRepository;

    @Autowired
    private CtxRepoRelationRepository relationRepository;

    @Autowired
    private CtxDimensionRepository dimensionRepository;

    @Autowired
    private CtxArchitecturePatternRepository patternRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private FakeChatModel fakeChatModel;

    private Team team;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("Gen Team", "orders-api");
        team = ctx.team();
        IngestionBatch batch1 = ctx.batch();
        batch1.setStatus(IngestionStatus.SUCCESS);
        batch1.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch1);
        wikiPageRepository.save(
                new CtxWikiPage(batch1, team.getId(), "orders-api", null, "overview", "Orders Overview", 0, "Orders"));

        Repository repo2 = testDataFactory.createRepository(
                team, "billing-api", TestDataFactory.getOrCreateDummyRepo(), "main", null);
        IngestionBatch batch2 = new IngestionBatch(repo2);
        batch2.setStatus(IngestionStatus.SUCCESS);
        batch2.setCommitHash("billing-commit");
        batch2.setActive(true);
        batch2 = ingestionBatchRepository.saveAndFlush(batch2);
        wikiPageRepository.save(new CtxWikiPage(
                batch2, team.getId(), "billing-api", null, "overview", "Billing Overview", 0, "Billing"));

        synopsisRepository.saveAndFlush(
                new CtxRepoSynopsis(batch1, RepoRole.SERVICE, List.of("orders"), "Handles orders", "{}"));
        dimensionRepository.saveAndFlush(new CtxDimension(
                batch1,
                team.getId(),
                DimensionCategory.DOMAIN,
                "Orders Domain",
                "Core orders",
                List.of("**/orders/**")));
        patternRepository.saveAndFlush(
                new CtxArchitecturePattern(batch1, team.getId(), "Hexagonal", "Ports and adapters", List.of()));
        boolean batch1First = batch1.getRepository()
                        .getId()
                        .toString()
                        .compareTo(batch2.getRepository().getId().toString())
                < 0;
        CtxRepoRelation rel = new CtxRepoRelation(
                team.getId(),
                batch1First ? batch1 : batch2,
                batch1First ? batch2 : batch1,
                Nature.CALLS,
                batch1First ? Direction.A_TO_B : Direction.B_TO_A,
                Ceiling.CONFIRMED);
        rel.setRationale("Billing calls Orders REST API");
        relationRepository.saveAndFlush(rel);
    }

    @AfterEach
    void tearDown() {
        fakeChatModel.reset();
        databaseCleaner.cleanAll();
    }

    @Test
    void generate_fullAndIncremental_createUpdateAndDeletePages() {
        TeamWikiBatch fullBatch = teamWikiBatchRepository.saveAndFlush(new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.MANUAL,
                snapshotService.captureCurrentState(team.getId()).snapshot()));

        AtomicInteger fullStep = new AtomicInteger(0);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    int step = fullStep.getAndIncrement();
                    if (step == 0) {
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of(
                                        "pageSlug",
                                        "team-overview",
                                        "title",
                                        "Team Landscape",
                                        "content",
                                        "Covers [Orders](/wiki/repo/orders-api/overview) and [Billing](/wiki/repo/billing-api)."));
                    }
                    if (step == 1) {
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of(
                                        "pageSlug",
                                        "legacy-cluster",
                                        "title",
                                        "Legacy Cluster",
                                        "parentPageSlug",
                                        "team-overview",
                                        "content",
                                        "Temporary cluster page."));
                    }
                    return textResponse("Team wiki generation complete.");
                })
                .build());

        var logger = new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService);
        var fullResult = generationService.generate(fullBatch.getId(), logger);
        assertThat(fullResult.pageCount()).isEqualTo(2);
        assertThat(fullResult.toolCalls()).isEqualTo(2L);

        fullBatch.setStatus(IngestionStatus.SUCCESS);
        fullBatch.setActive(true);
        teamWikiBatchRepository.saveAndFlush(fullBatch);

        // Now run INCREMENTAL build that updates team-overview and deletes legacy-cluster
        fakeChatModel.reset();
        TeamWikiBatch incBatch = teamWikiBatchRepository.saveAndFlush(new TeamWikiBatch(
                team,
                TeamWikiBuildMode.INCREMENTAL,
                TeamWikiTriggerKind.AUTOMATIC,
                snapshotService.captureCurrentState(team.getId()).snapshot()));

        AtomicInteger incStep = new AtomicInteger(0);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    int step = incStep.getAndIncrement();
                    if (step == 0) {
                        return multiToolResponse(
                                toolCall("list_team_wiki_pages", Map.of()),
                                toolCall("read_team_wiki_page", Map.of("pageSlug", "team-overview")));
                    }
                    if (step == 1) {
                        return multiToolResponse(
                                toolCall("delete_team_wiki_page", Map.of("pageSlug", "legacy-cluster")),
                                toolCall(
                                        "write_team_wiki_page",
                                        Map.of(
                                                "pageSlug",
                                                "team-overview",
                                                "title",
                                                "Team Landscape Updated",
                                                "content",
                                                "Updated links to [Orders](/wiki/repo/orders-api) and"
                                                        + " [Billing](/wiki/repo/billing-api).")));
                    }
                    return textResponse("Incremental update complete.");
                })
                .build());

        var incLogger = new TeamWikiBatchLogService.BatchLogger(incBatch.getId(), team.getId(), batchLogService);
        var incResult = generationService.generate(incBatch.getId(), incLogger);
        assertThat(incResult.pageCount()).isEqualTo(1);
        assertThat(incResult.toolCalls()).isEqualTo(4L);
        assertThat(wikiPageRepository.findByTeamWikiBatchIdAndPageSlug(incBatch.getId(), "team-overview"))
                .isPresent()
                .get()
                .extracting(CtxWikiPage::getTitle)
                .isEqualTo("Team Landscape Updated");
        assertThat(wikiPageRepository.findByTeamWikiBatchIdAndPageSlug(incBatch.getId(), "legacy-cluster"))
                .isEmpty();
    }

    private static ChatResponse toolResponse(String toolName, Map<String, Object> args) {
        return multiToolResponse(toolCall(toolName, args));
    }

    private static AssistantMessage.ToolCall toolCall(String toolName, Map<String, Object> args) {
        try {
            String json = new ObjectMapper().writeValueAsString(args);
            return new AssistantMessage.ToolCall("call-" + toolName, "function", toolName, json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatResponse multiToolResponse(AssistantMessage.ToolCall... calls) {
        AssistantMessage msg =
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
