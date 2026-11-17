package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.relations.SynopsisFragments;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService.SnapshotDiff;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiModelUsage;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RelationEntry;
import com.kratisai.controlplane.model.TeamWikiSourceSnapshot.RepoEntry;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.validation.mermaid.MermaidGrammar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TeamWikiGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(TeamWikiGenerationService.class);

    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxRepoSynopsisRepository synopsisRepository;
    private final CtxRepoRelationRepository relationRepository;
    private final CtxDimensionRepository dimensionRepository;
    private final CtxArchitecturePatternRepository patternRepository;
    private final TeamWikiSnapshotService snapshotService;
    private final ToolCallback[] fullModeCallbacks;
    private final ToolCallback[] incrementalModeCallbacks;
    private final Executor agentTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final int maxIterations;

    public TeamWikiGenerationService(
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            TeamWikiBatchRepository teamWikiBatchRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxRepoSynopsisRepository synopsisRepository,
            CtxRepoRelationRepository relationRepository,
            CtxDimensionRepository dimensionRepository,
            CtxArchitecturePatternRepository patternRepository,
            TeamWikiSnapshotService snapshotService,
            ListTeamWikiPagesTool listTeamWikiPagesTool,
            ReadTeamWikiPageTool readTeamWikiPageTool,
            WriteTeamWikiPageTool writeTeamWikiPageTool,
            DeleteTeamWikiPageTool deleteTeamWikiPageTool,
            TeamWikiRepoReadTools repoReadTools,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            PlatformTransactionManager transactionManager,
            IngestionProperties ingestionProperties) {
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.synopsisRepository = synopsisRepository;
        this.relationRepository = relationRepository;
        this.dimensionRepository = dimensionRepository;
        this.patternRepository = patternRepository;
        this.snapshotService = snapshotService;
        this.fullModeCallbacks = ToolCallbackProvider.from(ToolCallbacks.from(
                        listTeamWikiPagesTool, readTeamWikiPageTool, writeTeamWikiPageTool, repoReadTools))
                .getToolCallbacks();
        this.incrementalModeCallbacks = ToolCallbackProvider.from(ToolCallbacks.from(
                        listTeamWikiPagesTool,
                        readTeamWikiPageTool,
                        writeTeamWikiPageTool,
                        deleteTeamWikiPageTool,
                        repoReadTools))
                .getToolCallbacks();
        this.agentTaskExecutor = agentTaskExecutor;
        this.maxIterations = ingestionProperties.getTeamWiki().getMaxIterations();
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = tt;
    }

    public GenerationResult generate(UUID teamWikiBatchId, TeamWikiBatchLogService.BatchLogger batchLogger) {
        GenerationSetup setup = transactionTemplate.execute(status -> prepareGeneration(teamWikiBatchId));
        if (setup == null) {
            throw new IllegalStateException("Failed to prepare generation for team wiki batch " + teamWikiBatchId);
        }

        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(TeamWikiContext.BATCH_ID_KEY, teamWikiBatchId);
        contextMap.put(TeamWikiContext.REPO_BATCHES_KEY, setup.repoBatchIdsByName());
        contextMap.put(TeamWikiContext.LOGGER_KEY, batchLogger);
        ToolContext toolContext = new ToolContext(contextMap);

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(setup.systemPrompt()));
        history.add(new UserMessage(setup.userPrompt()));

        ToolCallback[] callbacks =
                setup.buildMode() == TeamWikiBuildMode.INCREMENTAL ? incrementalModeCallbacks : fullModeCallbacks;

        long[] toolCallCount = {0L};
        String result;
        try {
            result = ReActLoop.text(setup.chatClient(), callbacks)
                    .maxIterations(maxIterations)
                    .executor(agentTaskExecutor)
                    .onToolCallCount(count -> toolCallCount[0] += count)
                    .onThought((attempt, thought) -> logger.debug("GENERATE_TEAM_WIKI: {}", thought))
                    .onToolEvent((attempt, taskId, toolName, phase, detail) ->
                            logToolEvent(batchLogger, toolName, phase, detail))
                    .run(history, toolContext);
        } catch (ReActLoopFatalException e) {
            batchLogger.error(
                    "GENERATE_WIKI", "Team wiki generation aborted for " + teamWikiBatchId + ": " + e.getMessage());
            throw e;
        } catch (ReActLoopExhaustedException e) {
            String message = "GENERATE_WIKI failed for team wiki batch " + teamWikiBatchId
                    + ": Exceeded maximum iterations (" + maxIterations + ") without completion.";
            batchLogger.error("GENERATE_WIKI", message);
            throw new RuntimeException(message, e);
        }

        logger.info("GENERATE_TEAM_WIKI: LLM finished with message: {}", result);

        List<CtxWikiPage> generatedPages =
                transactionTemplate.execute(status -> ctxWikiPageRepository.findByTeamWikiBatchId(teamWikiBatchId));
        if (generatedPages == null || generatedPages.isEmpty()) {
            String message = "GENERATE_WIKI produced no team wiki pages for batch " + teamWikiBatchId;
            batchLogger.error("GENERATE_WIKI", message);
            throw new IllegalStateException(message);
        }

        int rootCount = transactionTemplate.execute(status -> ctxWikiPageRepository
                .findByTeamWikiBatchIdAndParentPageIsNullOrderByOrderIndexAsc(teamWikiBatchId)
                .size());
        if (rootCount > 1) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "Team wiki batch " + teamWikiBatchId + " has " + rootCount
                            + " top-level pages; expected a single root page.");
        }

        List<String> uncoveredRepos = findUncoveredRepos(setup.snapshot().repos(), generatedPages);
        if (!uncoveredRepos.isEmpty()) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "Team wiki batch " + teamWikiBatchId + " did not reference repositories: " + uncoveredRepos);
        }

        return new GenerationResult(generatedPages.size(), toolCallCount[0]);
    }

    private GenerationSetup prepareGeneration(UUID teamWikiBatchId) {
        TeamWikiBatch batch = teamWikiBatchRepository
                .findByIdWithTeam(teamWikiBatchId)
                .orElseThrow(() -> new IllegalStateException("TeamWikiBatch not found: " + teamWikiBatchId));
        Team team = batch.getTeam();
        TeamWikiSourceSnapshot snapshot = batch.getSourceSnapshot();
        if (snapshot == null || snapshot.repos().isEmpty()) {
            throw new IllegalStateException("TeamWikiBatch " + teamWikiBatchId + " has an empty source snapshot.");
        }

        TeamWikiBatch previousActive = teamWikiBatchRepository
                .findByTeamIdAndIsActiveTrue(team.getId())
                .orElse(null);

        TeamWikiBuildMode effectiveMode = batch.getBuildMode();
        if (effectiveMode == TeamWikiBuildMode.INCREMENTAL && previousActive != null) {
            copyPreviousPages(previousActive.getId(), batch);
        } else {
            effectiveMode = TeamWikiBuildMode.FULL;
        }

        Map<String, UUID> repoBatchIdsByName = new LinkedHashMap<>();
        for (RepoEntry repo : snapshot.repos()) {
            repoBatchIdsByName.put(repo.repoName(), repo.batchId());
        }

        String repoInventory = buildRepoInventory(snapshot.repos());
        String relationsSection = buildRelationsSection(team.getId());
        String deltaSection = effectiveMode == TeamWikiBuildMode.INCREMENTAL
                ? buildDeltaSection(previousActive.getSourceSnapshot(), snapshot)
                : "";

        String systemPrompt =
                buildSystemPrompt(team.getName(), effectiveMode, repoInventory, relationsSection, deltaSection);
        String userPrompt = effectiveMode == TeamWikiBuildMode.INCREMENTAL
                ? "Inspect the existing team wiki pages and the structural changes, then update, add, or remove"
                        + " pages so the team wiki accurately covers all active repositories and clusters."
                : "Analyze the team's active repositories and cross-repository relations, then generate the"
                        + " team-wide wiki documentation.";

        return new GenerationSetup(
                chatClientFor(batch, team), effectiveMode, snapshot, repoBatchIdsByName, systemPrompt, userPrompt);
    }

    private void copyPreviousPages(UUID previousBatchId, TeamWikiBatch targetBatch) {
        List<CtxWikiPage> sourcePages = ctxWikiPageRepository.findByTeamWikiBatchId(previousBatchId);
        if (sourcePages.isEmpty()) {
            return;
        }
        Map<UUID, CtxWikiPage> clonesByOldId = new HashMap<>();
        for (CtxWikiPage src : sourcePages) {
            CtxWikiPage clone = new CtxWikiPage(
                    targetBatch,
                    targetBatch.getTeam().getId(),
                    null,
                    src.getPageSlug(),
                    src.getTitle(),
                    src.getOrderIndex(),
                    src.getContent());
            clonesByOldId.put(src.getId(), clone);
        }
        for (CtxWikiPage src : sourcePages) {
            if (src.getParentPage() != null) {
                CtxWikiPage clonedChild = clonesByOldId.get(src.getId());
                CtxWikiPage clonedParent = clonesByOldId.get(src.getParentPage().getId());
                clonedChild.setParentPage(clonedParent);
            }
        }
        ctxWikiPageRepository.saveAllAndFlush(clonesByOldId.values());
    }

    private String buildRepoInventory(List<RepoEntry> repos) {
        List<UUID> batchIds = repos.stream().map(RepoEntry::batchId).toList();
        Map<UUID, CtxRepoSynopsis> synopses = new HashMap<>();
        for (CtxRepoSynopsis s : synopsisRepository.findByBatchIdIn(batchIds)) {
            synopses.put(s.getBatch().getId(), s);
        }

        StringBuilder sb = new StringBuilder();
        for (RepoEntry repo : repos) {
            CtxRepoSynopsis synopsis = synopses.get(repo.batchId());
            sb.append("### ").append(repo.repoName()).append('\n');
            sb.append("- Role: ")
                    .append(repo.repoRole() != null ? repo.repoRole() : "UNKNOWN")
                    .append('\n');
            if (synopsis != null && !synopsis.getKnownAs().isEmpty()) {
                sb.append("- Known As: ")
                        .append(String.join(", ", synopsis.getKnownAs()))
                        .append('\n');
            }
            if (synopsis != null && synopsis.getSynopsis() != null) {
                sb.append("- Synopsis:\n")
                        .append(new SynopsisFragments(synopsis.getSynopsis()).render())
                        .append('\n');
            }

            List<CtxDimension> dimensions = dimensionRepository.findByBatchId(repo.batchId());
            if (!dimensions.isEmpty()) {
                sb.append("- Key Dimensions:\n");
                dimensions.stream().limit(8).forEach(d -> {
                    sb.append("  - [").append(d.getCategory()).append("] ").append(d.getName());
                    if (d.getSynopsis() != null && !d.getSynopsis().isBlank()) {
                        String brief = d.getSynopsis().replace('\n', ' ');
                        if (brief.length() > 180) {
                            brief = brief.substring(0, 177) + "...";
                        }
                        sb.append(": ").append(brief);
                    }
                    sb.append('\n');
                });
            }

            List<CtxArchitecturePattern> patterns = patternRepository.findByBatchId(repo.batchId());
            if (!patterns.isEmpty()) {
                sb.append("- Architecture Patterns: ");
                sb.append(String.join(
                        ", ",
                        patterns.stream().map(CtxArchitecturePattern::getName).toList()));
                sb.append('\n');
            }

            List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(repo.batchId());
            if (!pages.isEmpty()) {
                sb.append("- Repo Wiki Pages (link via `/wiki/repo/")
                        .append(repo.repoName())
                        .append("/{pageSlug}`):\n");
                pages.forEach(p -> sb.append("  - `")
                        .append(p.getPageSlug())
                        .append("` — ")
                        .append(p.getTitle())
                        .append('\n'));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String buildRelationsSection(UUID teamId) {
        List<CtxRepoRelation> relations = relationRepository.findCurrentByTeamId(teamId);
        if (relations.isEmpty()) {
            return "No cross-repository relations detected. All active repositories are currently standalone.";
        }
        StringBuilder sb = new StringBuilder();
        for (CtxRepoRelation rel : relations) {
            String sentence = TeamWikiSnapshotService.renderRelation(
                    rel.getBatchA().getRepository().getName(),
                    rel.getBatchB().getRepository().getName(),
                    rel.getNature(),
                    rel.getDirection());
            sb.append("- ")
                    .append(sentence)
                    .append(" [")
                    .append(rel.getConfidence())
                    .append("]");
            if (rel.getRationale() != null && !rel.getRationale().isBlank()) {
                sb.append(" — ").append(rel.getRationale());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    String buildDeltaSection(TeamWikiSourceSnapshot previous, TeamWikiSourceSnapshot current) {
        SnapshotDiff diff =
                snapshotService.diff(previous, new TeamWikiSnapshotService.SnapshotState(current, Map.of(), Map.of()));
        StringBuilder sb = new StringBuilder();
        sb.append("## Structural & Repository Delta Since Last Team Wiki\n");
        if (!diff.addedRepos().isEmpty()) {
            sb.append("- Added repositories: ")
                    .append(diff.addedRepos().stream().map(RepoEntry::repoName).toList())
                    .append('\n');
        }
        if (!diff.removedRepos().isEmpty()) {
            sb.append("- Removed repositories (prune stale references/pages): ")
                    .append(diff.removedRepos().stream()
                            .map(RepoEntry::repoName)
                            .toList())
                    .append('\n');
        }
        if (!diff.roleChanges().isEmpty()) {
            sb.append("- Role changes: ");
            sb.append(diff.roleChanges().stream()
                    .map(rc -> rc.repoName() + " (" + rc.previousRole() + " -> " + rc.currentRole() + ")")
                    .toList());
            sb.append('\n');
        }
        if (!diff.addedRelations().isEmpty()) {
            sb.append("- Added relations:\n");
            for (RelationEntry rel : diff.addedRelations()) {
                sb.append("  - ")
                        .append(TeamWikiSnapshotService.renderRelation(
                                rel.repoAName(), rel.repoBName(), rel.nature(), rel.direction()))
                        .append(" [")
                        .append(rel.confidence())
                        .append("]\n");
            }
        }
        if (!diff.removedRelations().isEmpty()) {
            sb.append("- Removed relations:\n");
            for (RelationEntry rel : diff.removedRelations()) {
                sb.append("  - ")
                        .append(TeamWikiSnapshotService.renderRelation(
                                rel.repoAName(), rel.repoBName(), rel.nature(), rel.direction()))
                        .append('\n');
            }
        }
        if (!diff.reingestedRepos().isEmpty()) {
            sb.append("- Re-ingested repositories (check if cluster summaries need refresh): ")
                    .append(diff.reingestedRepos().stream()
                            .map(RepoEntry::repoName)
                            .toList())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String buildSystemPrompt(
            String teamName,
            TeamWikiBuildMode mode,
            String repoInventory,
            String relationsSection,
            String deltaSection) {
        String modeGuidance = mode == TeamWikiBuildMode.INCREMENTAL ? """
                  Build Mode: INCREMENTAL UPDATE.
                  The previous team wiki pages have been pre-loaded into this build.
                  1. Call `list_team_wiki_pages` and `read_team_wiki_page` to inspect the existing structure.
                  2. Update only the pages affected by the delta below, add pages for new clusters/repos, and call
                     `delete_team_wiki_page` for any obsolete pages (for example, deleted repositories).
                  3. Preserve unchanged pages as-is so prior work is not discarded.
                  """ : """
                  Build Mode: FULL REBUILD.
                  Write the single top-level root page FIRST (omit `parentPageSlug`), then create child pages beneath it.
                  """;

        return """
                You are Kratis, an expert enterprise architect and technical writer.
                Your task is to author the Team-Wide Wiki for team "%s".
                This wiki is the primary jumping-off point for team members: it surfaces cross-repository architecture,
                system clusters, shared contracts, platform/infrastructure boundaries, and team-level concepts that sit
                above individual repositories.

                CRITICAL RULES:
                1. **Summarize & Link, Do Not Duplicate**: Never copy repository-internal file listings or deep
                   implementation details from per-repo wikis. Summarize the cross-repo picture and link to per-repo
                   wikis using exact Markdown links: `[Repo Name](/wiki/repo/{repoName})` or
                   `[Page Title](/wiki/repo/{repoName}/{pageSlug})`.
                2. **Cluster & Standalone Coverage**: Infer natural functional clusters / sub-clusters from the
                   cross-repository relations and synopses (distinguishing shared platform/library repos from business
                   clusters). Summarise and document clusters independently.  Avoid stuffing unrelated repos into shared
                   diagrams etc. Explicitly cover EVERY active ingested repository—including standalone repositories that
                   have no cross-repo relations.  Don't infer clusters where there are no relations.
                3. **Hierarchy**: Maintain exactly one top-level root page (no `parentPageSlug`) and well-organized
                   child pages beneath it (e.g., system landscape overview, one page per major cluster/sub-cluster,
                   cross-cutting/shared infrastructure, and standalone services).
                4. **Diagrams & Tables**: Include clear Mermaid architecture/sequence diagrams and tables where helpful.
                   %s
                5. **Prose**: Write prose following ASD-STE100 Simplified Technical English.
                6. **Completion**: When done, return a concise plain-text confirmation such as "Team wiki generation complete."

                %s

                %s

                ## Active Ingested Repositories
                %s

                ## Cross-Repository Relations
                %s
                """.formatted(
                        teamName, MermaidGrammar.GUIDANCE, modeGuidance, deltaSection, repoInventory, relationsSection);
    }

    private ChatClient chatClientFor(TeamWikiBatch batch, Team team) {
        ModelProvider modelProvider = team.getIngestionProvider();
        String modelName = team.getIngestionModel();
        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = batch.modelUsageFor(ModelKind.CHAT)
                .map(TeamWikiModelUsage::getVirtualKey)
                .orElse(null);
        return ChatClient.builder(
                        chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false))
                .build();
    }

    void logToolEvent(
            TeamWikiBatchLogService.BatchLogger batchLogger,
            String toolName,
            ReActLoop.ToolPhase phase,
            String detail) {
        boolean detailAddsContext = detail != null && !detail.isBlank() && !detail.equals(toolName);
        switch (phase) {
            case START ->
                batchLogger.debug(
                        "GENERATE_WIKI", "Calling tool: " + toolName + (detailAddsContext ? " - " + detail : ""));
            case COMPLETE -> logger.debug("GENERATE_TEAM_WIKI: tool {} completed", toolName);
            case ERROR ->
                batchLogger.warn("GENERATE_WIKI", "Tool execution failed (retrying): " + toolName + " - " + detail);
        }
    }

    static List<String> findUncoveredRepos(List<RepoEntry> repos, List<CtxWikiPage> pages) {
        String combined =
                pages.stream().map(p -> p.getTitle() + "\n" + p.getContent()).reduce("", (a, b) -> a + "\n" + b);
        List<String> missing = new ArrayList<>();
        for (RepoEntry repo : repos) {
            if (!combined.contains(repo.repoName())) {
                missing.add(repo.repoName());
            }
        }
        return missing;
    }

    public record GenerationResult(int pageCount, long toolCalls) {}

    private record GenerationSetup(
            ChatClient chatClient,
            TeamWikiBuildMode buildMode,
            TeamWikiSourceSnapshot snapshot,
            Map<String, UUID> repoBatchIdsByName,
            String systemPrompt,
            String userPrompt) {}
}
