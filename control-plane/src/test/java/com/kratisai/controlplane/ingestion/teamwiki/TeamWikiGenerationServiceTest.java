package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
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
import com.kratisai.controlplane.testsupport.WikiPageContentFixtures;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        rel.setCitations("{\"matches\":[{\"rationale\":\"Billing calls Orders REST API\"}]}");
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
        AtomicReference<String> fullPrompt = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    fullPrompt.set(prompt.getContents());
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
                                        WikiPageContentFixtures.WIKI_PAGE_BODY
                                                + " Covers [Orders](/wiki/repo/orders-api/overview) and"
                                                + " [Billing](/wiki/repo/billing-api)."));
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
                                        WikiPageContentFixtures.WIKI_PAGE_BODY + " Temporary cluster page."));
                    }
                    return textResponse("Team wiki generation complete.");
                })
                .build());

        var logger = new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService);
        var fullResult = generationService.generate(fullBatch.getId(), logger);
        assertThat(fullResult.pageCount()).isEqualTo(2);
        assertThat(fullResult.toolCalls()).isEqualTo(2L);
        assertThat(fullPrompt.get()).contains("Billing calls Orders REST API");

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
                                                WikiPageContentFixtures.WIKI_PAGE_BODY
                                                        + " Updated links to [Orders](/wiki/repo/orders-api) and"
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

    @Test
    void generate_incrementalRepairsStaleRepoLinkOnUntouchedCopiedPage() {
        TeamWikiBatch previous = activeTeamWikiBatchWith(WikiPageContentFixtures.WIKI_PAGE_BODY
                + " Legacy link to [Gone](/wiki/repo/gone-api/overview) and [Billing](/wiki/repo/billing-api).");

        TeamWikiBatch incBatch = newBatch(TeamWikiBuildMode.INCREMENTAL);
        AtomicInteger steps = new AtomicInteger(0);
        AtomicReference<String> repairPrompt = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    int step = steps.getAndIncrement();
                    if (step == 0) {
                        return textResponse("Incremental update complete.");
                    }
                    if (repairPrompt.get() == null && prompt.getContents().contains("The team wiki is incomplete")) {
                        repairPrompt.set(prompt.getContents());
                    }
                    return toolResponse(
                            "write_team_wiki_page",
                            Map.of(
                                    "pageSlug",
                                    "team-overview",
                                    "title",
                                    "Team Landscape",
                                    "content",
                                    WikiPageContentFixtures.WIKI_PAGE_BODY
                                            + " Links [Orders](/wiki/repo/orders-api) and"
                                            + " [Billing](/wiki/repo/billing-api)."));
                })
                .build());

        var result = generationService.generate(
                incBatch.getId(),
                new TeamWikiBatchLogService.BatchLogger(incBatch.getId(), team.getId(), batchLogService));

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(repairPrompt.get()).contains("/wiki/repo/gone-api/overview");
        assertThat(wikiPageRepository
                        .findByTeamWikiBatchIdAndPageSlug(incBatch.getId(), "team-overview")
                        .orElseThrow()
                        .getContent())
                .doesNotContain("gone-api");
        assertThat(previous).isNotNull();
    }

    @Test
    void generate_incrementalFailsWhenStaleRepoLinkIsNotRepaired() {
        activeTeamWikiBatchWith(
                WikiPageContentFixtures.WIKI_PAGE_BODY + " Legacy link to [Gone](/wiki/repo/gone-api).");
        TeamWikiBatch incBatch = newBatch(TeamWikiBuildMode.INCREMENTAL);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(new AssistantMessage("Incremental update complete."))
                .build());

        assertThatThrownBy(() -> generationService.generate(
                        incBatch.getId(),
                        new TeamWikiBatchLogService.BatchLogger(incBatch.getId(), team.getId(), batchLogService)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repository links that are broken")
                .hasMessageContaining("gone-api");
    }

    @Test
    void generate_failsWhenActiveRepositoryIsNotLinkedFromTheWiki() {
        TeamWikiBatch fullBatch = newBatch(TeamWikiBuildMode.FULL);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> toolResponse(
                        "write_team_wiki_page",
                        Map.of(
                                "pageSlug",
                                "team-overview",
                                "title",
                                "Team Landscape",
                                "content",
                                WikiPageContentFixtures.WIKI_PAGE_BODY
                                        + " Links [Orders](/wiki/repo/orders-api) only.")))
                .build());

        assertThatThrownBy(() -> generationService.generate(
                        fullBatch.getId(),
                        new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active repositories with no team wiki link")
                .hasMessageContaining("billing-api");
    }

    @Test
    void generate_failsWhenTheWikiHasMultipleRoots() {
        TeamWikiBatch fullBatch = newBatch(TeamWikiBuildMode.FULL);
        AtomicInteger steps = new AtomicInteger(0);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    int step = steps.getAndIncrement();
                    String bothRepos = WikiPageContentFixtures.WIKI_PAGE_BODY
                            + " Links [Orders](/wiki/repo/orders-api) and [Billing](/wiki/repo/billing-api).";
                    if (step == 0) {
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of("pageSlug", "team-overview", "title", "Team Landscape", "content", bothRepos));
                    }
                    if (step == 1) {
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of("pageSlug", "cluster", "title", "Cluster", "content", bothRepos));
                    }
                    return textResponse("Team wiki generation complete.");
                })
                .build());

        assertThatThrownBy(() -> generationService.generate(
                        fullBatch.getId(),
                        new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple root pages");
    }

    @Test
    void generate_exhaustionSucceedsWhenSavedWikiIsCoherent() {
        TeamWikiBatch fullBatch = newBatch(TeamWikiBuildMode.FULL);
        AtomicInteger steps = new AtomicInteger(0);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    if (steps.getAndIncrement() == 0) {
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of(
                                        "pageSlug",
                                        "team-overview",
                                        "title",
                                        "Team Landscape",
                                        "content",
                                        WikiPageContentFixtures.WIKI_PAGE_BODY
                                                + " Links [Orders](/wiki/repo/orders-api) and"
                                                + " [Billing](/wiki/repo/billing-api)."));
                    }
                    return multiToolResponse(toolCall("list_team_wiki_pages", Map.of()));
                })
                .build());

        var result = generationService.generate(
                fullBatch.getId(),
                new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService));

        assertThat(result.pageCount()).isEqualTo(1);
    }

    @Test
    void generate_replaysWikiStatusOnLaterTurnsWithoutDuplicatingIt() {
        TeamWikiBatch fullBatch = newBatch(TeamWikiBuildMode.FULL);
        AtomicReference<String> firstPrompt = new AtomicReference<>();
        AtomicReference<String> secondPrompt = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger(0);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    int step = steps.getAndIncrement();
                    if (step == 0) {
                        firstPrompt.set(prompt.getContents());
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of(
                                        "pageSlug",
                                        "team-overview",
                                        "title",
                                        "Team Landscape",
                                        "content",
                                        WikiPageContentFixtures.WIKI_PAGE_BODY
                                                + " Links [Orders](/wiki/repo/orders-api) and"
                                                + " [Billing](/wiki/repo/billing-api)."));
                    }
                    secondPrompt.set(prompt.getContents());
                    return textResponse("Team wiki generation complete.");
                })
                .build());

        generationService.generate(
                fullBatch.getId(),
                new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService));

        assertThat(firstPrompt.get()).doesNotContain("- Saved pages (");
        assertThat(secondPrompt.get()).contains("Current team wiki status").contains("Saved pages (1): team-overview");
        assertThat(countOccurrences(secondPrompt.get(), "Current team wiki status:"))
                .isEqualTo(1);
    }

    @Test
    void generate_usesSnapshotRelationsInsteadOfRelationsCreatedAfterCapture() {
        TeamWikiSourceSnapshot snapshot =
                snapshotService.captureCurrentState(team.getId()).snapshot();
        Repository extra = testDataFactory.createRepository(
                team, "shipping-api", TestDataFactory.getOrCreateDummyRepo(), "main", null);
        IngestionBatch pending = new IngestionBatch(extra);
        pending.setStatus(IngestionStatus.SUCCESS);
        pending.setCommitHash("shipping-commit");
        pending.setActive(true);
        IngestionBatch extraBatch = ingestionBatchRepository.saveAndFlush(pending);
        IngestionBatch existing = ingestionBatchRepository.findActiveByTeamId(team.getId()).stream()
                .filter(candidate -> !candidate.getId().equals(extraBatch.getId()))
                .findFirst()
                .orElseThrow();
        boolean extraFirst = extraBatch
                        .getRepository()
                        .getId()
                        .toString()
                        .compareTo(existing.getRepository().getId().toString())
                < 0;
        relationRepository.saveAndFlush(new CtxRepoRelation(
                team.getId(),
                extraFirst ? extraBatch : existing,
                extraFirst ? existing : extraBatch,
                Nature.CALLS,
                extraFirst ? Direction.A_TO_B : Direction.B_TO_A,
                Ceiling.CONFIRMED));

        TeamWikiBatch fullBatch = teamWikiBatchRepository.saveAndFlush(
                new TeamWikiBatch(team, TeamWikiBuildMode.FULL, TeamWikiTriggerKind.MANUAL, snapshot));
        AtomicReference<String> promptText = new AtomicReference<>();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> true)
                .response(prompt -> {
                    if (promptText.get() == null) {
                        promptText.set(prompt.getContents());
                        return toolResponse(
                                "write_team_wiki_page",
                                Map.of(
                                        "pageSlug",
                                        "team-overview",
                                        "title",
                                        "Team Landscape",
                                        "content",
                                        WikiPageContentFixtures.WIKI_PAGE_BODY
                                                + " Links [Orders](/wiki/repo/orders-api) and"
                                                + " [Billing](/wiki/repo/billing-api)."));
                    }
                    return textResponse("Team wiki generation complete.");
                })
                .build());

        generationService.generate(
                fullBatch.getId(),
                new TeamWikiBatchLogService.BatchLogger(fullBatch.getId(), team.getId(), batchLogService));

        assertThat(promptText.get()).contains("Billing calls Orders REST API");
        assertThat(promptText.get()).doesNotContain("shipping-api");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private TeamWikiBatch newBatch(TeamWikiBuildMode mode) {
        return teamWikiBatchRepository.saveAndFlush(new TeamWikiBatch(
                team,
                mode,
                TeamWikiTriggerKind.MANUAL,
                snapshotService.captureCurrentState(team.getId()).snapshot()));
    }

    private TeamWikiBatch activeTeamWikiBatchWith(String content) {
        TeamWikiBatch previous = newBatch(TeamWikiBuildMode.FULL);
        previous.setStatus(IngestionStatus.SUCCESS);
        previous.setActive(true);
        teamWikiBatchRepository.saveAndFlush(previous);
        wikiPageRepository.saveAndFlush(
                new CtxWikiPage(previous, team.getId(), null, "team-overview", "Team Landscape", 0, content));
        return previous;
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
