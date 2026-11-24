package com.kratisai.controlplane.ingestion.teamwiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.relations.SynopsisFragments;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService.SnapshotDiff;
import com.kratisai.controlplane.ingestion.write.WikiPageLinkExtractor;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RelationRationales;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
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
    private static final int COMPLETION_TURNS = 12;

    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxRepoSynopsisRepository synopsisRepository;
    private final CtxRepoRelationRepository relationRepository;
    private final ObjectMapper objectMapper;
    private final CtxDimensionRepository dimensionRepository;
    private final CtxArchitecturePatternRepository patternRepository;
    private final TeamWikiSnapshotService snapshotService;
    private final WikiPageLinkExtractor wikiPageLinkExtractor;
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
            ObjectMapper objectMapper,
            CtxDimensionRepository dimensionRepository,
            CtxArchitecturePatternRepository patternRepository,
            TeamWikiSnapshotService snapshotService,
            WikiPageLinkExtractor wikiPageLinkExtractor,
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
        this.objectMapper = objectMapper;
        this.dimensionRepository = dimensionRepository;
        this.patternRepository = patternRepository;
        this.snapshotService = snapshotService;
        this.wikiPageLinkExtractor = wikiPageLinkExtractor;
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

        ToolCallback[] callbacks =
                setup.buildMode() == TeamWikiBuildMode.INCREMENTAL ? incrementalModeCallbacks : fullModeCallbacks;

        long[] toolCallCount = {0L};
        String result = runPass(
                setup, callbacks, teamWikiBatchId, toolContext, batchLogger, maxIterations, null, toolCallCount);
        boolean exhausted = result == null;

        TeamWikiAudit audit = auditSnapshot(teamWikiBatchId, setup);
        if (!audit.problems().isEmpty()) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "Team wiki batch " + teamWikiBatchId + " needs repair: " + String.join("; ", audit.problems())
                            + ". Running one bounded completion pass.");
            runPass(
                    setup,
                    callbacks,
                    teamWikiBatchId,
                    toolContext,
                    batchLogger,
                    COMPLETION_TURNS,
                    repairPrompt(audit),
                    toolCallCount);
            audit = auditSnapshot(teamWikiBatchId, setup);
        }
        if (!audit.problems().isEmpty()) {
            String message = "GENERATE_WIKI produced an incomplete team wiki for batch " + teamWikiBatchId + ": "
                    + String.join("; ", audit.problems()) + ".";
            batchLogger.error("GENERATE_WIKI", message);
            throw new IllegalStateException(message);
        }

        List<String> unmentionedRepos = findUncoveredRepos(setup.snapshot().repos(), audit.pages());
        if (!unmentionedRepos.isEmpty()) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "Team wiki batch " + teamWikiBatchId + " did not mention repositories by name: "
                            + unmentionedRepos);
        }
        if (exhausted) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "Team wiki batch " + teamWikiBatchId + " exhausted " + maxIterations
                            + " turns; keeping the saved wiki because the audit passed.");
        }

        return new GenerationResult(audit.savedSlugs().size(), toolCallCount[0]);
    }

    /**
     * Runs one bounded agent pass. A null {@code repairInstruction} runs the original build task; otherwise the
     * instruction is appended as a further user turn. The result is {@code null} when the pass exhausted its turn
     * budget, which the caller resolves through the audit rather than by discarding the saved pages.
     */
    private String runPass(
            GenerationSetup setup,
            ToolCallback[] callbacks,
            UUID teamWikiBatchId,
            ToolContext toolContext,
            TeamWikiBatchLogService.BatchLogger batchLogger,
            int maxTurns,
            String repairInstruction,
            long[] toolCallCount) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(setup.systemPrompt()));
        history.add(new UserMessage(setup.userPrompt()));
        if (repairInstruction != null) {
            history.add(new UserMessage(repairInstruction));
        }
        try {
            String result = ReActLoop.text(setup.chatClient(), callbacks)
                    .maxIterations(maxTurns)
                    .executor(agentTaskExecutor)
                    .turnFeedbackSupplier(() -> buildTeamWikiStatus(teamWikiBatchId, setup))
                    .onToolCallCount(count -> toolCallCount[0] += count)
                    .onThought((attempt, thought) -> logger.debug("GENERATE_TEAM_WIKI: {}", thought))
                    .onToolEvent((attempt, taskId, toolName, phase, detail) ->
                            logToolEvent(batchLogger, toolName, phase, detail))
                    .run(history, toolContext);
            logger.info("GENERATE_TEAM_WIKI: LLM finished with message: {}", result);
            return result;
        } catch (ReActLoopFatalException e) {
            batchLogger.error(
                    "GENERATE_WIKI", "Team wiki generation aborted for " + teamWikiBatchId + ": " + e.getMessage());
            throw e;
        } catch (ReActLoopExhaustedException e) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "GENERATE_WIKI exhausted " + maxTurns + " turns for team wiki batch " + teamWikiBatchId
                            + "; auditing the saved pages instead of discarding them.");
            return null;
        }
    }

    /** Descriptive, live state for the model; identical shape to the repair audit so both stay in step. */
    private String buildTeamWikiStatus(UUID teamWikiBatchId, GenerationSetup setup) {
        List<CtxWikiPage> pages = readPages(teamWikiBatchId);
        if (pages.isEmpty()) return "";
        TeamWikiAudit audit = audit(pages, setup);
        StringBuilder status = new StringBuilder("Current team wiki status:\n");
        status.append("- Root pages: ")
                .append(audit.rootSlugs().size())
                .append(" — ")
                .append(audit.rootSlugs().isEmpty() ? "(none)" : String.join(", ", audit.rootSlugs()))
                .append(audit.rootSlugs().size() == 1 ? "" : " (exactly one root is required)")
                .append('\n');
        status.append("- Saved pages (")
                .append(audit.savedSlugs().size())
                .append("): ")
                .append(String.join(", ", audit.savedSlugs()))
                .append('\n');
        status.append("- Team page links with no matching page: ")
                .append(renderLinkMap(audit.missingTeamLinks()))
                .append('\n');
        status.append("- Repository wiki links that are broken: ")
                .append(renderLinkMap(audit.brokenRepoLinks()))
                .append('\n');
        status.append("- Active repositories with no team wiki link: ")
                .append(audit.unlinkedRepos().isEmpty() ? "(none)" : String.join(", ", audit.unlinkedRepos()))
                .append('\n');
        if (!audit.unreachableSlugs().isEmpty()) {
            status.append("- Pages not reachable from the root: ")
                    .append(String.join(", ", audit.unreachableSlugs()))
                    .append('\n');
        }
        return status.toString();
    }

    private static String renderLinkMap(Map<String, List<String>> links) {
        if (links.isEmpty()) return "(none - all links resolve)";
        return links.entrySet().stream()
                .map(entry -> entry.getKey() + " (referenced by: " + String.join(", ", entry.getValue()) + ")")
                .toList()
                .toString();
    }

    private String repairPrompt(TeamWikiAudit audit) {
        StringBuilder prompt = new StringBuilder("""
                The team wiki is incomplete. Fix these defects, then confirm completion:
                """);
        for (String problem : audit.problems()) {
            prompt.append("- ").append(problem).append('\n');
        }
        prompt.append('\n')
                .append("Saved pages: ")
                .append(audit.savedSlugs().isEmpty() ? "(none)" : String.join(", ", audit.savedSlugs()))
                .append('\n');
        if (!audit.missingTeamLinks().isEmpty()) {
            prompt.append("Create the missing team pages with write_team_wiki_page using the slugs named above, or ")
                    .append("remove the links that point at them.\n");
        }
        if (!audit.brokenRepoLinks().isEmpty()) {
            prompt.append("Repair the broken repository links listed above. Repository links must use the exact ")
                    .append("form /wiki/repo/{repoName} or /wiki/repo/{repoName}/{pageSlug} for repositories and ")
                    .append("pages that exist in this build. Update the pages that contain them.\n");
        }
        if (!audit.unlinkedRepos().isEmpty()) {
            prompt.append("Add links to every active repository named above; each must be reachable from the team ")
                    .append("wiki through at least one /wiki/repo/{repoName} link.\n");
        }
        if (audit.rootSlugs().size() > 1) {
            prompt.append("Keep '")
                    .append(audit.rootSlugs().getFirst())
                    .append("' as the only root: re-save the other top-level pages with its slug as parentPageSlug.\n");
        }
        if (!audit.unreachableSlugs().isEmpty()) {
            prompt.append("Re-save the unreachable pages with a parentPageSlug of an existing page so they sit ")
                    .append("under the root.\n");
        }
        prompt.append("\nWrite independent pages in parallel within one turn where possible.");
        return prompt.toString();
    }

    private TeamWikiAudit auditSnapshot(UUID teamWikiBatchId, GenerationSetup setup) {
        return audit(readPages(teamWikiBatchId), setup);
    }

    private List<CtxWikiPage> readPages(UUID teamWikiBatchId) {
        List<CtxWikiPage> pages = transactionTemplate.execute(
                status -> ctxWikiPageRepository.findByTeamWikiBatchIdWithParent(teamWikiBatchId));
        return pages == null ? List.of() : pages;
    }

    /**
     * Ground truth over the persisted team wiki: one root, everything reachable, every team and repository
     * link resolvable against this build's snapshot, and every active repository linked.
     */
    private TeamWikiAudit audit(List<CtxWikiPage> pages, GenerationSetup setup) {
        Map<String, CtxWikiPage> bySlug = new LinkedHashMap<>();
        for (CtxWikiPage page : pages) bySlug.putIfAbsent(page.getPageSlug(), page);
        List<String> savedSlugs = List.copyOf(bySlug.keySet());
        List<String> rootSlugs = CtxWikiPages.topLevelPages(pages).stream()
                .map(CtxWikiPage::getPageSlug)
                .toList();

        Set<String> repoNames = setup.repoPageSlugsByName().keySet();
        Map<String, List<String>> missingTeamLinks = new LinkedHashMap<>();
        Map<String, List<String>> brokenRepoLinks = new LinkedHashMap<>();
        Set<String> linkedRepos = new LinkedHashSet<>();
        for (CtxWikiPage page : pages) {
            WikiPageLinkExtractor.WikiLinks links = wikiPageLinkExtractor.extractWikiLinks(page.getContent());
            for (String slug : links.teamPageSlugs()) {
                if (!bySlug.containsKey(slug)) {
                    addReferrer(missingTeamLinks, slug, page.getPageSlug());
                }
            }
            for (String repoPath : links.repoPaths()) {
                Optional<TeamWikiLinkResolver.RepoTarget> resolved = TeamWikiLinkResolver.resolve(repoPath, repoNames);
                if (resolved.isEmpty()) {
                    addReferrer(brokenRepoLinks, "/wiki/repo/" + repoPath, page.getPageSlug());
                    continue;
                }
                TeamWikiLinkResolver.RepoTarget target = resolved.get();
                linkedRepos.add(target.repoName());
                String pageSlug = target.pageSlug();
                if (pageSlug != null
                        && !setup.repoPageSlugsByName()
                                .getOrDefault(target.repoName(), Set.of())
                                .contains(pageSlug)) {
                    addReferrer(brokenRepoLinks, "/wiki/repo/" + repoPath, page.getPageSlug());
                }
            }
        }

        List<String> unlinkedRepos =
                repoNames.stream().filter(name -> !linkedRepos.contains(name)).toList();
        List<String> unreachable = rootSlugs.size() == 1 ? unreachableFromRoot(pages, rootSlugs.getFirst()) : List.of();

        List<String> problems = new ArrayList<>();
        if (pages.isEmpty()) {
            problems.add("no team wiki pages were written");
        } else if (rootSlugs.isEmpty()) {
            problems.add("no root page: every page has a parentPageSlug");
        } else if (rootSlugs.size() > 1) {
            problems.add("multiple root pages: " + String.join(", ", rootSlugs));
        }
        if (!missingTeamLinks.isEmpty()) {
            problems.add("team links with no matching page: " + renderLinkMap(missingTeamLinks));
        }
        if (!brokenRepoLinks.isEmpty()) {
            problems.add("repository links that are broken: " + renderLinkMap(brokenRepoLinks));
        }
        if (!unlinkedRepos.isEmpty()) {
            problems.add("active repositories with no team wiki link: " + String.join(", ", unlinkedRepos));
        }
        if (!unreachable.isEmpty()) {
            problems.add("pages not reachable from the root: " + String.join(", ", unreachable));
        }
        return new TeamWikiAudit(
                pages,
                savedSlugs,
                rootSlugs,
                Map.copyOf(missingTeamLinks),
                Map.copyOf(brokenRepoLinks),
                unlinkedRepos,
                unreachable,
                List.copyOf(problems));
    }

    private static void addReferrer(Map<String, List<String>> targets, String key, String referrer) {
        List<String> referrers = targets.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!referrers.contains(referrer)) referrers.add(referrer);
    }

    private List<String> unreachableFromRoot(List<CtxWikiPage> pages, String rootSlug) {
        Map<String, CtxWikiPage> bySlug = new LinkedHashMap<>();
        for (CtxWikiPage page : pages) bySlug.putIfAbsent(page.getPageSlug(), page);
        CtxWikiPage root = bySlug.get(rootSlug);
        if (root == null) return List.of();

        Set<String> visited = new LinkedHashSet<>();
        Deque<CtxWikiPage> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            CtxWikiPage current = queue.removeFirst();
            if (current.getId() == null || !visited.add(current.getPageSlug())) continue;
            queue.addAll(CtxWikiPages.childPages(pages, current.getId()));
        }
        return pages.stream()
                .map(CtxWikiPage::getPageSlug)
                .filter(slug -> !visited.contains(slug))
                .toList();
    }

    private record TeamWikiAudit(
            List<CtxWikiPage> pages,
            List<String> savedSlugs,
            List<String> rootSlugs,
            Map<String, List<String>> missingTeamLinks,
            Map<String, List<String>> brokenRepoLinks,
            List<String> unlinkedRepos,
            List<String> unreachableSlugs,
            List<String> problems) {}

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

        RepoInventory inventory = buildRepoInventory(snapshot.repos());
        String relationsSection = buildRelationsSection(snapshot, team.getId());
        String deltaSection = effectiveMode == TeamWikiBuildMode.INCREMENTAL
                ? buildDeltaSection(previousActive.getSourceSnapshot(), snapshot)
                : "";

        String systemPrompt =
                buildSystemPrompt(team.getName(), effectiveMode, inventory.text(), relationsSection, deltaSection);
        String userPrompt = effectiveMode == TeamWikiBuildMode.INCREMENTAL
                ? "Inspect the existing team wiki pages and the structural changes, then update, add, or remove"
                        + " pages so the team wiki accurately covers all active repositories and clusters."
                : "Analyze the team's active repositories and cross-repository relations, then generate the"
                        + " team-wide wiki documentation.";

        return new GenerationSetup(
                chatClientFor(batch, team),
                effectiveMode,
                snapshot,
                repoBatchIdsByName,
                inventory.pageSlugsByName(),
                systemPrompt,
                userPrompt);
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

    private record RepoInventory(String text, Map<String, Set<String>> pageSlugsByName) {}

    private RepoInventory buildRepoInventory(List<RepoEntry> repos) {
        List<UUID> batchIds = repos.stream().map(RepoEntry::batchId).toList();
        Map<UUID, CtxRepoSynopsis> synopses = new HashMap<>();
        for (CtxRepoSynopsis s : synopsisRepository.findByBatchIdIn(batchIds)) {
            synopses.put(s.getBatch().getId(), s);
        }
        Map<String, Set<String>> pageSlugsByName = new LinkedHashMap<>();

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
            pageSlugsByName.put(
                    repo.repoName(),
                    pages.stream().map(CtxWikiPage::getPageSlug).collect(Collectors.toCollection(LinkedHashSet::new)));
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
        return new RepoInventory(sb.toString().stripTrailing(), Map.copyOf(pageSlugsByName));
    }

    /**
     * Renders the relations captured in this build's snapshot, not whatever is current in the database:
     * relations created after the batch was queued would otherwise describe repositories the wiki inventory
     * does not contain.
     */
    private String buildRelationsSection(TeamWikiSourceSnapshot snapshot, UUID teamId) {
        List<RelationEntry> relations = snapshot.relations();
        if (relations.isEmpty()) {
            return "No cross-repository relations detected. All active repositories are currently standalone.";
        }
        Map<String, CtxRepoRelation> rationales = indexRelationsBySnapshotBatches(snapshot, teamId);

        StringBuilder sb = new StringBuilder();
        for (RelationEntry rel : relations) {
            sb.append("- ")
                    .append(TeamWikiSnapshotService.renderRelation(
                            rel.repoAName(), rel.repoBName(), rel.nature(), rel.direction()))
                    .append(" [")
                    .append(rel.confidence())
                    .append("]");
            CtxRepoRelation stored =
                    rationales.get(relationKey(rel.repoAId(), rel.repoBId(), rel.nature(), rel.direction()));
            if (stored != null) {
                String rationale = String.join(
                        "; ", RelationRationales.extract(stored.getCitations(), stored.getId(), objectMapper));
                if (!rationale.isBlank()) {
                    sb.append(" — ").append(rationale);
                }
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private Map<String, CtxRepoRelation> indexRelationsBySnapshotBatches(TeamWikiSourceSnapshot snapshot, UUID teamId) {
        Map<UUID, UUID> batchIdByRepoId = new HashMap<>();
        for (RepoEntry repo : snapshot.repos()) {
            batchIdByRepoId.put(repo.repoId(), repo.batchId());
        }
        Map<String, CtxRepoRelation> indexed = new HashMap<>();
        for (CtxRepoRelation rel : relationRepository.findCurrentByTeamId(teamId)) {
            UUID snapshotBatchA =
                    batchIdByRepoId.get(rel.getBatchA().getRepository().getId());
            UUID snapshotBatchB =
                    batchIdByRepoId.get(rel.getBatchB().getRepository().getId());
            if (snapshotBatchA == null
                    || snapshotBatchB == null
                    || !snapshotBatchA.equals(rel.getBatchA().getId())
                    || !snapshotBatchB.equals(rel.getBatchB().getId())) {
                continue;
            }
            indexed.put(
                    relationKey(rel.getRepositoryAId(), rel.getRepositoryBId(), rel.getNature(), rel.getDirection()),
                    rel);
        }
        return indexed;
    }

    private static String relationKey(UUID repoAId, UUID repoBId, Nature nature, Direction direction) {
        return repoAId + "|" + repoBId + "|" + nature + "|" + direction;
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
                  4. Repair links on pages you did not otherwise change: the 'Current team wiki status' block reports
                     repository links that no longer resolve because a repository or page was removed or re-ingested.
                  5. Keep exactly one root page. Omitting parentPageSlug when updating a page keeps that page's
                     current parent; pass parentPageSlug explicitly only to create a child page or to reparent.
                  """ : """
                  Build Mode: FULL REBUILD.
                  Write the single top-level root page FIRST (omit `parentPageSlug`), and make its body a table of
                  contents that links to every child page you plan to write, using either a relative slug
                  (`[Cluster](orders-checkout-cluster)`) or `/wiki/team/{pageSlug}`.
                  Once the root page is saved, write its child pages: issue all sibling write_team_wiki_page calls in
                  the same turn so they run in parallel, then repeat for the next level.
                  For child pages, specify parentPageSlug and an orderIndex (e.g. 10, 20, 30) to establish logical
                  narrative order. An orderIndex is expected for every child page; when one is missing, the page is
                  appended after its current siblings.
                  Omitting parentPageSlug when updating an existing page keeps that page's current parent.
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
                   cross-cutting/shared infrastructure, and standalone services). Every link you write must point at a
                   page that exists before you finish.
                4. **Diagrams & Tables**: Include clear Mermaid architecture/sequence diagrams and tables where helpful.
                   %s
                5. **Prose**: Write at least 120 prose words on every page: headings, code blocks, and raw HTML are
                   excluded, and table text counts as prose. Do not pad with repetitive text. Write prose following
                   ASD-STE100 Simplified Technical English.
                6. **Coverage**: Every active ingested repository listed below must be linked from the wiki with a
                   `/wiki/repo/{repoName}` link.
                7. **Completion**: When done, return a concise plain-text confirmation such as "Team wiki generation complete."

                A 'Current team wiki status' block is appended to these instructions on every turn. It reports saved
                pages, links with no matching page, broken repository links, and repositories that nothing links to.
                Treat it as ground truth and finish only when it reports no gaps.

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

    /** Advisory only: name mentions are not proof of coverage, which the audit checks through links. */
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
            Map<String, Set<String>> repoPageSlugsByName,
            String systemPrompt,
            String userPrompt) {}
}
