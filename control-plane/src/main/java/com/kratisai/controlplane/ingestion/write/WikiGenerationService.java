package com.kratisai.controlplane.ingestion.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ListWikiPagesTool;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.ingestion.ReadWikiPageTool;
import com.kratisai.controlplane.ingestion.WriteWikiPageTool;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.CtxWikiPages;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.service.RepositoryIntelligenceTools;
import com.kratisai.controlplane.validation.mermaid.MermaidGrammar;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

@Service
public class WikiGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(WikiGenerationService.class);
    private static final int MAX_TURNS = 30;
    private static final int COMPLETION_TURNS = 12;

    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final RepositoryIntelligenceTools repositoryIntelligenceTools;
    private final ToolCallback[] toolCallbacks;
    private final IngestionUsageTracker usageTracker;
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final WikiPageLinkExtractor wikiPageLinkExtractor;
    private final String baseCloneDir;
    private final String sccBinaryPath;
    private final Executor agentTaskExecutor;

    public WikiGenerationService(
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            RepositoryIntelligenceTools repositoryIntelligenceTools,
            ReadFileTool readFileTool,
            ReadWikiPageTool readWikiPageTool,
            ListWikiPagesTool listWikiPagesTool,
            WriteWikiPageTool writeWikiPageTool,
            IngestionUsageTracker usageTracker,
            ProcessExecutor processExecutor,
            ObjectMapper objectMapper,
            CtxWikiPageRepository ctxWikiPageRepository,
            WikiPageLinkExtractor wikiPageLinkExtractor,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            IngestionProperties ingestionProperties) {
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.repositoryIntelligenceTools = repositoryIntelligenceTools;
        this.toolCallbacks = ToolCallbackProvider.from(ToolCallbacks.from(
                        repositoryIntelligenceTools,
                        readFileTool,
                        readWikiPageTool,
                        listWikiPagesTool,
                        writeWikiPageTool))
                .getToolCallbacks();
        this.usageTracker = usageTracker;
        this.processExecutor = processExecutor;
        this.objectMapper = objectMapper;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.wikiPageLinkExtractor = wikiPageLinkExtractor;
        this.agentTaskExecutor = agentTaskExecutor;
        this.baseCloneDir = ingestionProperties.getCloneDir();
        this.sccBinaryPath = ingestionProperties.getParser().getSccBinaryPath();
    }

    private ChatClient chatClientFor(IngestionBatch batch) {
        Team team = batch.getRepository().getTeam();
        ModelProvider modelProvider = team.getIngestionProvider();
        String modelName = team.getIngestionModel();
        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = batch.modelUsageFor(ModelKind.CHAT)
                .map(IngestionModelUsage::getVirtualKey)
                .orElse(null);
        return ChatClient.builder(
                        chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false))
                .build();
    }

    public String generateWiki(IngestionBatch batch, IngestionBatchLogService.BatchLogger batchLogger) {
        ToolContext toolContext = new ToolContext(Map.of("batchId", batch.getId(), "wikiBatchLogger", batchLogger));
        String patternsContext = repositoryIntelligenceTools.getArchitecturalPatterns(toolContext);
        String dimensionsContext = repositoryIntelligenceTools.getDimensions(toolContext);

        RepoStats stats = getRepositoryStats(batch);
        int targetPages = calculateTargetPageCount(stats.linesOfCode(), stats.filesCount());
        String classification = getComplexityClassification(stats.linesOfCode(), stats.filesCount());

        String systemPrompt = buildSystemPrompt(stats, classification, targetPages, patternsContext, dimensionsContext);
        ChatClient chatClient = chatClientFor(batch);

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage("Please analyze the repository and generate the wiki documentation."));

        boolean exhausted = false;
        String result = null;
        try {
            result = runAgent(chatClient, history, toolContext, batch, batchLogger, MAX_TURNS);
        } catch (ReActLoopFatalException e) {
            batchLogger.error(
                    "GENERATE_WIKI",
                    "GENERATE_WIKI aborted for " + batch.getId() + " with a non-recoverable LLM error: "
                            + e.getMessage());
            throw e;
        } catch (ReActLoopExhaustedException e) {
            exhausted = true;
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "GENERATE_WIKI exhausted " + MAX_TURNS
                            + " turns; auditing the saved pages instead of discarding them.");
        }

        if (!exhausted && result == null) {
            throw new IllegalStateException("GENERATE_WIKI returned no completion result for batch " + batch.getId());
        }
        if (!exhausted) logger.info("GENERATE_WIKI: LLM finished with message: {}", result);

        WikiAudit audit = audit(ctxWikiPageRepository.findByBatchIdWithParent(batch.getId()));
        if (!audit.problems().isEmpty()) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "Wiki for batch " + batch.getId() + " needs repair: " + String.join("; ", audit.problems())
                            + ". Running one bounded completion pass.");
            runCompletionPass(chatClient, systemPrompt, toolContext, batch, batchLogger, audit);
            audit = audit(ctxWikiPageRepository.findByBatchIdWithParent(batch.getId()));
        }
        if (!audit.problems().isEmpty()) {
            String message = "GENERATE_WIKI produced an incomplete wiki for batch " + batch.getId() + ": "
                    + String.join("; ", audit.problems()) + ".";
            batchLogger.error("GENERATE_WIKI", message);
            throw new IllegalStateException(message);
        }

        return "generated " + audit.savedSlugs().size() + " page(s)" + (exhausted ? " after turn exhaustion" : "");
    }

    private String runAgent(
            ChatClient chatClient,
            List<Message> history,
            ToolContext toolContext,
            IngestionBatch batch,
            IngestionBatchLogService.BatchLogger batchLogger,
            int maxTurns) {
        return ReActLoop.text(chatClient, toolCallbacks)
                .maxIterations(maxTurns)
                .executor(agentTaskExecutor)
                .turnFeedbackSupplier(() -> buildWikiStatus(batch.getId()))
                .onToolCallCount(count -> usageTracker.recordToolCalls(batch.getId(), count))
                .onThought((attempt, thought) -> logger.debug("GENERATE_WIKI: {}", thought))
                .onToolEvent((attempt, taskId, toolName, phase, detail) ->
                        logToolEvent(batchLogger, toolName, phase, detail))
                .run(history, toolContext);
    }

    /** Descriptive, live state for the model; never an instruction, so it is safe on the final turn. */
    private String buildWikiStatus(UUID batchId) {
        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchIdWithParent(batchId);
        if (pages.isEmpty()) return "";
        WikiAudit audit = audit(pages);
        StringBuilder status = new StringBuilder("Current wiki status for this batch:\n");
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
        status.append("- Links that resolve: ")
                .append(audit.resolvedSlugs().isEmpty() ? "(none)" : String.join(", ", audit.resolvedSlugs()))
                .append('\n');
        status.append("- Links with no matching page: ")
                .append(
                        audit.missingLinks().isEmpty()
                                ? "(none - all internal links resolve)"
                                : audit.missingLinks().entrySet().stream()
                                        .map(entry -> entry.getKey() + " (referenced by: "
                                                + String.join(", ", entry.getValue()) + ")")
                                        .toList()
                                        .toString())
                .append('\n');
        if (!audit.unreachableSlugs().isEmpty()) {
            status.append("- Pages not reachable from the root: ")
                    .append(String.join(", ", audit.unreachableSlugs()))
                    .append('\n');
        }
        return status.toString();
    }

    /**
     * Ground truth over the persisted wiki: one root, everything reachable from it, and every internal
     * link pointing at a saved page. Dotted names such as {@code README.md} are file references, not pages.
     */
    private WikiAudit audit(List<CtxWikiPage> pages) {
        Map<String, CtxWikiPage> bySlug = new LinkedHashMap<>();
        for (CtxWikiPage page : pages) bySlug.putIfAbsent(page.getPageSlug(), page);
        List<String> savedSlugs = List.copyOf(bySlug.keySet());
        List<String> rootSlugs = CtxWikiPages.topLevelPages(pages).stream()
                .map(CtxWikiPage::getPageSlug)
                .toList();

        Set<String> referenced = new LinkedHashSet<>();
        Map<String, List<String>> missingLinks = new LinkedHashMap<>();
        for (CtxWikiPage page : pages) {
            for (String slug : wikiPageLinkExtractor.internalSlugs(page.getContent())) {
                referenced.add(slug);
                if (!bySlug.containsKey(slug)) {
                    missingLinks
                            .computeIfAbsent(slug, ignored -> new ArrayList<>())
                            .add(page.getPageSlug());
                }
            }
        }
        Set<String> resolved = new LinkedHashSet<>(referenced);
        resolved.retainAll(bySlug.keySet());

        List<String> unreachable = rootSlugs.size() == 1 ? unreachableFromRoot(pages, rootSlugs.getFirst()) : List.of();

        List<String> problems = new ArrayList<>();
        if (pages.isEmpty()) {
            problems.add("no wiki pages were written");
        } else if (rootSlugs.isEmpty()) {
            problems.add("no root page: every page has a parentPageSlug");
        } else if (rootSlugs.size() > 1) {
            problems.add("multiple root pages: " + String.join(", ", rootSlugs));
        }
        if (!missingLinks.isEmpty()) {
            problems.add("links with no matching page: "
                    + missingLinks.entrySet().stream()
                            .map(entry ->
                                    entry.getKey() + " (referenced by: " + String.join(", ", entry.getValue()) + ")")
                            .toList());
        }
        if (!unreachable.isEmpty()) {
            problems.add("pages not reachable from the root: " + String.join(", ", unreachable));
        }
        return new WikiAudit(
                savedSlugs,
                rootSlugs,
                List.copyOf(resolved),
                Map.copyOf(missingLinks),
                unreachable,
                List.copyOf(problems));
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

    private record WikiAudit(
            List<String> savedSlugs,
            List<String> rootSlugs,
            List<String> resolvedSlugs,
            Map<String, List<String>> missingLinks,
            List<String> unreachableSlugs,
            List<String> problems) {}

    private void runCompletionPass(
            ChatClient chatClient,
            String systemPrompt,
            ToolContext toolContext,
            IngestionBatch batch,
            IngestionBatchLogService.BatchLogger batchLogger,
            WikiAudit audit) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage("Please analyze the repository and generate the wiki documentation."));
        history.add(new UserMessage(auditSummary(audit)));
        try {
            runAgent(chatClient, history, toolContext, batch, batchLogger, COMPLETION_TURNS);
        } catch (ReActLoopFatalException e) {
            batchLogger.error(
                    "GENERATE_WIKI",
                    "GENERATE_WIKI completion pass aborted with a non-recoverable LLM error: " + e.getMessage());
            throw e;
        } catch (ReActLoopExhaustedException e) {
            batchLogger.warn(
                    "GENERATE_WIKI",
                    "GENERATE_WIKI completion pass exhausted " + COMPLETION_TURNS + " turns; re-auditing saved pages.");
        }
    }

    private static String auditSummary(WikiAudit audit) {
        StringBuilder summary = new StringBuilder("""
                The wiki is incomplete. Fix these defects, then confirm completion:
                """);
        for (String problem : audit.problems()) {
            summary.append("- ").append(problem).append('\n');
        }
        summary.append('\n')
                .append("Saved pages: ")
                .append(audit.savedSlugs().isEmpty() ? "(none)" : String.join(", ", audit.savedSlugs()))
                .append('\n');
        if (!audit.missingLinks().isEmpty()) {
            summary.append("Create the missing pages with write_wiki_page, giving each the slug named above, ")
                    .append("parentPageSlug of the page that links to it, and at least 120 prose words. ")
                    .append("Alternatively remove or correct the link if that page does not belong in the wiki.\n");
        }
        if (audit.rootSlugs().size() > 1) {
            summary.append("Keep '")
                    .append(audit.rootSlugs().getFirst())
                    .append("' as the only root: re-save the other top-level pages with its slug as parentPageSlug.\n");
        }
        if (!audit.unreachableSlugs().isEmpty()) {
            summary.append("Re-save the unreachable pages with a parentPageSlug of an existing page so they sit ")
                    .append("under the root.\n");
        }
        summary.append("\nWrite sibling pages in parallel within one turn where possible.");
        return summary.toString();
    }

    private String buildSystemPrompt(
            RepoStats stats, String classification, int targetPages, String patternsContext, String dimensionsContext) {
        return """
                You are Kratis, an expert technical writer and autonomous architect.
                You have a codebase under analysis, and must synthesize the human-readable high-level and architectural Markdown documentation for this codebase.
                You have access to tools to gather information: read_file, read_wiki_page, list_wiki_pages, and write_wiki_page. Parallelize read tools when useful.
                Use them judiciously to read the repository state and iteratively build the wiki. Do not make unnecessary tool calls - summarise. You can read a maximum of 10 files.
                The wiki must have exactly one top-level page (no parent) that roots the hierarchy, plus child pages beneath it.

                Codebase Complexity Analysis:
                - Approximate Lines of Code: %d
                - Approximate Number of Files: %d
                - Classification: %s
                - Suggested Target Number of Wiki Pages: %d

                Please use the suggested target number of pages as a guide. A simpler repository should have fewer pages (e.g. 2 pages) while a complex repository should have more pages (up to 20 pages) to cover the repository's topics accurately but concisely. The suggested count is advisory, not a minimum or a completeness check. A smaller wiki is acceptable when it covers the repository. Ensure content is sufficient for a high-level technical review.

                Writing order:
                1. Write the single top-level page first, omitting parentPageSlug. Its body must contain a Wiki Map: a table of contents linking to every child page you intend to write, using relative links such as `[Business Context](business-context)`.
                2. Once the root page is saved, write its child pages: issue all sibling write_wiki_page calls in the same turn so they run in parallel, then repeat for the next level.
                3. Every page you link to must exist before you finish, and every page you write must be linked from the Wiki Map.

                Prose depth: write at least 120 prose words on every page: headings, code blocks, and raw HTML are excluded, and table text counts as prose. Do not pad with repetitive text.

                A 'Current wiki status' block is appended to these instructions on every turn. It reports saved pages, links that resolve, and links with no matching page. Use it as ground truth and leave it with no gaps.

                Omitting parentPageSlug when updating an existing page keeps that page's current parent; pass it explicitly only to create a child page or to reparent.

                If a tool call returns an ERROR, do not give up: read the error, correct the arguments, and retry that tool. Unknown parent slugs, thin pages and invalid Mermaid syntax are recoverable errors. When the wiki is complete and the status block reports no gaps, your final response MUST be a simple confirmation message like "Wiki generation complete." Do not return a JSON object.

                Your first task is to perform a high-level technical review of the repository and decide the structure of the wiki.  You should review the full scope for the wiki, and determine the hierarchy of pages.

                Consider the inclusion of the following sections:
                - Introduction: A high-level overview of the repository and its purpose.
                - Business Context: A deep-dive of the business domain and its context.
                - Architecture: A detailed description of the codebase's architecture and its components.
                - Code Organization: A breakdown of the codebase's structure, including folders and files.
                - Reusable Patterns: Code and design patterns used in the codebase.
                - Testing: Guidelines for testing the codebase's functionality.
                - Deployment: Instructions for deploying the codebase to production.

                The wiki for a simple repository may not need all sections. A complex wiki may require multiple pages for each section.

                Each page should be well-structured and self-contained, with a summary of the topic and all relevant information to support the summary.
                Where appropriate, use at least one mermaid diagrams or sequence diagrams per page. Include tables in preference to overuse of bullet points.
                %s

                Write prose following ASD-STE100 Simplified Technical English.

                ---

                Here is the pre-fetched high-level context for the repository:
                Architectural Patterns:
                %s

                Code Dimensions:
                %s
                """.formatted(
                        stats.linesOfCode(),
                        stats.filesCount(),
                        classification,
                        targetPages,
                        MermaidGrammar.GUIDANCE,
                        patternsContext,
                        dimensionsContext);
    }

    private void logToolEvent(
            IngestionBatchLogService.BatchLogger batchLogger,
            String toolName,
            ReActLoop.ToolPhase phase,
            String detail) {
        boolean detailAddsContext = detail != null && !detail.isBlank() && !detail.equals(toolName);
        switch (phase) {
            case START -> {
                logger.debug("GENERATE_WIKI: calling tool {}{}", toolName, detailAddsContext ? " - " + detail : "");
                batchLogger.debug(
                        "GENERATE_WIKI", "Calling tool: " + toolName + (detailAddsContext ? " - " + detail : ""));
            }
            case COMPLETE -> logger.debug("GENERATE_WIKI: tool {} completed", toolName);
            case ERROR -> {
                logger.debug("GENERATE_WIKI: tool {} failed: {}", toolName, detail);
                batchLogger.warn("GENERATE_WIKI", "Tool execution failed (retrying): " + toolName + " - " + detail);
            }
        }
    }

    public record RepoStats(int linesOfCode, int filesCount) {}

    private RepoStats getRepositoryStats(IngestionBatch batch) {
        File cloneDir = Path.of(baseCloneDir, batch.getId().toString()).toFile();

        try {
            if (!cloneDir.exists() || !cloneDir.isDirectory()) {
                throw new IllegalStateException(
                        "Clone directory " + cloneDir + " does not exist or is not a directory.");
            }
            String absSccBinaryPath = Path.of(sccBinaryPath).toAbsolutePath().toString();
            ProcessExecutor.ProcessResult result =
                    processExecutor.execute(List.of(absSccBinaryPath, "-f", "json"), cloneDir);
            if (result.exitCode() != 0) {
                throw new IllegalStateException("scc execution failed with exit code: " + result.exitCode());
            }
            String output = new String(result.output(), StandardCharsets.UTF_8);
            RepoStats stats = parseSccOutput(output);
            logger.info(
                    "Successfully gathered repository stats using scc: {} lines of code in {} files",
                    stats.linesOfCode(),
                    stats.filesCount());
            return stats;
        } catch (Exception e) {
            logger.error("scc execution or parsing failed: {}", e.getMessage(), e);
            throw new RuntimeException("Repository metrics analysis failed because scc is missing or failed", e);
        }
    }

    public RepoStats parseSccOutput(String output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            int code = 0;
            int files = 0;

            if (root.isArray()) {
                // This looks like scc output format
                for (JsonNode node : root) {
                    code += node.path("Code").asInt(0);
                    files += node.path("Count").asInt(0);
                }
            } else {
                throw new IllegalArgumentException(
                        "Expected a JSON array for scc output but got: " + root.getNodeType());
            }

            return new RepoStats(code, files);
        } catch (Exception e) {
            logger.warn("Failed to parse scc output: {}", e.getMessage());
            throw new IllegalArgumentException("Failed to parse scc output: " + e.getMessage(), e);
        }
    }

    public double calculateCompositeScore(int linesOfCode, int filesCount) {
        double locRatio = Math.clamp((linesOfCode - 2000.0) / (15000 - 2000), 0.0, 1.0);
        double filesRatio = Math.clamp((filesCount - 20.0) / (150 - 20), 0.0, 1.0);
        return 0.6 * locRatio + 0.4 * filesRatio;
    }

    public int calculateTargetPageCount(int linesOfCode, int filesCount) {
        double compositeScore = calculateCompositeScore(linesOfCode, filesCount);
        return (int) Math.round(2.0 + compositeScore * 18.0);
    }

    public String getComplexityClassification(int linesOfCode, int filesCount) {
        double compositeScore = calculateCompositeScore(linesOfCode, filesCount);
        if (compositeScore < 0.15) {
            return "Simple";
        } else if (compositeScore >= 0.85) {
            return "Complex";
        } else {
            return "Medium";
        }
    }
}
