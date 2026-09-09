package com.kratisai.controlplane.ingestion.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.ingestion.ReadWikiPageTool;
import com.kratisai.controlplane.ingestion.WriteWikiPageTool;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.service.RepositoryIntelligenceTools;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WikiGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(WikiGenerationService.class);

    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final RepositoryIntelligenceTools repositoryIntelligenceTools;
    private final ToolCallback[] toolCallbacks;
    private final IngestionBatchLogService ingestionBatchLogService;
    private final IngestionUsageTracker usageTracker;
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final String baseCloneDir;
    private final String sccBinaryPath;
    private final Executor agentTaskExecutor;

    public WikiGenerationService(
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            RepositoryIntelligenceTools repositoryIntelligenceTools,
            ReadFileTool readFileTool,
            ReadWikiPageTool readWikiPageTool,
            WriteWikiPageTool writeWikiPageTool,
            IngestionBatchLogService ingestionBatchLogService,
            IngestionUsageTracker usageTracker,
            ProcessExecutor processExecutor,
            ObjectMapper objectMapper,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            @Value("${kratis.ingestion.clone-dir:${java.io.tmpdir}/kratis-ingest}") String baseCloneDir,
            @Value("${kratis.parser.scc-binary-path}") String sccBinaryPath) {
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.repositoryIntelligenceTools = repositoryIntelligenceTools;
        this.toolCallbacks = ToolCallbackProvider.from(ToolCallbacks.from(
                        repositoryIntelligenceTools, readFileTool, readWikiPageTool, writeWikiPageTool))
                .getToolCallbacks();
        this.ingestionBatchLogService = ingestionBatchLogService;
        this.usageTracker = usageTracker;
        this.processExecutor = processExecutor;
        this.objectMapper = objectMapper;
        this.agentTaskExecutor = agentTaskExecutor;
        this.baseCloneDir = baseCloneDir;
        this.sccBinaryPath = sccBinaryPath;
    }

    private ChatClient chatClientFor(IngestionBatch batch) {
        Team team = batch.getRepository().getTeam();
        ModelProvider modelProvider = team.getIngestionProvider();
        String modelName = team.getIngestionModel();
        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = batch.getUsage().getVirtualKey();
        return ChatClient.builder(
                        chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false))
                .build();
    }

    public void generateWiki(IngestionBatch batch) {
        IngestionBatchLogService.BatchLogger batchLogger = new IngestionBatchLogService.BatchLogger(
                batch.getId(), batch.getRepository().getTeam().getId(), ingestionBatchLogService);
        batchLogger.info("GENERATE_WIKI", "Starting Agentic ReAct loop for Target Wiki Generation.");

        ToolContext toolContext = new ToolContext(Map.of("batchId", batch.getId()));
        String patternsContext = repositoryIntelligenceTools.getArchitecturalPatterns(toolContext);
        String dimensionsContext = repositoryIntelligenceTools.getDimensions(toolContext);

        RepoStats stats = getRepositoryStats(batch);
        int targetPages = calculateTargetPageCount(stats.linesOfCode(), stats.filesCount());
        String classification = getComplexityClassification(stats.linesOfCode(), stats.filesCount());

        String systemPrompt = """
                You are Kratis, an expert technical writer and autonomous architect.
                You have a codebase under analysis, and must synthesize the human-readable high-level and architectural Markdown documentation for this codebase.
                You have access to tools to gather information: read_file, read_wiki_page, and write_wiki_page. Parallelize tools when possible.
                Use them judiciously to read the repository state and iteratively build the wiki. Do not make unnecessary tool calls - summarise. You can read a maximum of 10 files.
                You are not constrained to a specific number of pages. The wiki should consist of ONE page. top-level page (no parent) and a hierarchy of child pages.

                Codebase Complexity Analysis:
                - Approximate Lines of Code: %d
                - Approximate Number of Files: %d
                - Classification: %s
                - Suggested Target Number of Wiki Pages: %d

                Please use the suggested target number of pages as a guide. A simpler repository should have fewer pages (e.g. 2 pages) while a complex repository should have more pages (up to 20 pages) to cover the repository's topics accurately but concisely. Ensure content is sufficient for a high-level technical review.

                Use the write_wiki_page tool to create or update wiki pages. Provide a unique pageSlug, a title, and the markdown content.
                Once you have gathered the required information and written the necessary wiki pages, your final response MUST be a simple confirmation message like "Wiki generation complete."
                Do not return a JSON object. Just confirm completion.

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

                After determining the structure of the wiki, you should use the write_wiki_page tool to create the initial pages.
                Each page should be well-structured and self-contained, with a summary of the topic and all relavent information to support the summary.
                Where appropriate, use at least one mermaid diagrams or sequence diagrams per page. Include tables in preference to overuse of bullet points.

                Mermaid syntax rules (mandatory, the wiki renderer parses these strictly):
                - Always quote edge labels if they contain spaces, parentheses, colons, slashes, or special characters, e.g. -->|"Generate Virtual Key (TTL + Budget)"| or -->|"POST /key/generate"|.
                - Always quote or bracket any subgraph or node title/label that contains spaces, parentheses, colons, braces, brackets, quotes, or hashes, e.g. subgraph "Control Plane Tier (Spring Boot)" or subgraph cpt[Control Plane Tier (Spring Boot)].
                - A subgraph without a quoted/bracketed title must contain only simple word characters and spaces, e.g. subgraph Client Tier.
                - Always close every subgraph with "end".
                - Use the official Mermaid flowchart, sequence, and classDiagram grammars only.
                - If a diagram would be syntactically fragile, prefer a simpler diagram over an invalid one.

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
                        patternsContext,
                        dimensionsContext);

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage("Please analyze the repository and generate the wiki documentation."));

        int maxTurns = 20;
        String result;
        try {
            result = ReActLoop.text(chatClientFor(batch), toolCallbacks)
                    .maxIterations(maxTurns)
                    .executor(agentTaskExecutor)
                    .onToolCallCount(count -> usageTracker.recordToolCalls(batch.getId(), count))
                    .onThought(thought -> logger.debug("GENERATE_WIKI: {}", thought))
                    .onToolEvent(
                            (taskId, toolName, phase, detail) -> logToolEvent(batchLogger, toolName, phase, detail))
                    .run(history, toolContext);
        } catch (ReActLoopFatalException e) {
            batchLogger.error(
                    "GENERATE_WIKI",
                    "GENERATE_WIKI aborted for " + batch.getId() + " with a non-recoverable LLM error: "
                            + e.getMessage());
            throw e;
        } catch (ReActLoopExhaustedException e) {
            String message = "GENERATE_WIKI failed for " + batch.getId()
                    + " : Exceeded maximum iterations ("
                    + maxTurns
                    + ") without yielding final completion message.";
            batchLogger.error("GENERATE_WIKI", message);
            throw new RuntimeException(message, e);
        }

        logger.info("GENERATE_WIKI: LLM finished with message: {}", result);
        batchLogger.info("GENERATE_WIKI", "Target Wiki Generation completed successfully.");
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
                batchLogger.info(
                        "GENERATE_WIKI", "Calling tool: " + toolName + (detailAddsContext ? " - " + detail : ""));
            }
            case COMPLETE -> logger.debug("GENERATE_WIKI: tool {} completed", toolName);
            case ERROR -> {
                logger.debug("GENERATE_WIKI: tool {} failed: {}", toolName, detail);
                batchLogger.error("GENERATE_WIKI", "Tool execution failed: " + toolName + " - " + detail);
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
