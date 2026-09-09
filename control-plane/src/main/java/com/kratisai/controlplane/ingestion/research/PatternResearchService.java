package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoopExhaustedException;
import com.kratisai.controlplane.agentloop.ReActLoopFatalException;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatternResearchService {

    private static final Logger logger = LoggerFactory.getLogger(PatternResearchService.class);

    private final CtxNodeRepository ctxNodeRepository;
    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final CtxArchitecturePatternRepository ctxArchitecturePatternRepository;
    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final IngestionBatchLogService ingestionBatchLogService;
    private final IngestionUsageTracker usageTracker;
    private final ToolCallback[] toolCallbacks;
    private final Executor agentTaskExecutor;

    public PatternResearchService(
            CtxNodeRepository ctxNodeRepository,
            CtxDimensionRepository ctxDimensionRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            IngestionBatchLogService ingestionBatchLogService,
            IngestionUsageTracker usageTracker,
            ReadFileTool readFileTool,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.ingestionBatchLogService = ingestionBatchLogService;
        this.usageTracker = usageTracker;
        this.agentTaskExecutor = agentTaskExecutor;
        this.toolCallbacks =
                ToolCallbackProvider.from(ToolCallbacks.from(readFileTool)).getToolCallbacks();
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

    @Transactional
    public void researchPatterns(IngestionBatch batch) {
        var batchLogger = new IngestionBatchLogService.BatchLogger(
                batch.getId(), batch.getRepository().getTeam().getId(), ingestionBatchLogService);

        List<CtxDimension> archetypeDimensions = ctxDimensionRepository.findByBatchId(batch.getId()).stream()
                .filter(d -> d.getCategory() == DimensionCategory.ARCHETYPE)
                .toList();

        if (archetypeDimensions.isEmpty()) {
            batchLogger.info(
                    "RESEARCH_PATTERNS", "No ARCHETYPE dimensions found. Skipping architecture pattern deduction.");
            return;
        }

        StringBuilder archetypeContext = new StringBuilder();
        for (CtxDimension dimension : archetypeDimensions) {
            List<CtxNodeDimension> topNodes = ctxNodeDimensionRepository.findByDimensionId(dimension.getId()).stream()
                    .filter(nd -> nd.getRankScore() != null && nd.getRankScore() > 0.0)
                    .sorted(Comparator.comparing(CtxNodeDimension::getRankScore).reversed())
                    .limit(5) // Top 5 exemplar files per archetype
                    .toList();

            if (topNodes.isEmpty()) {
                continue;
            }

            archetypeContext.append("Archetype: ").append(dimension.getName()).append("\n");
            if (dimension.getSynopsis() != null && !dimension.getSynopsis().isBlank()) {
                archetypeContext
                        .append("Synopsis: ")
                        .append(dimension.getSynopsis())
                        .append("\n");
            }
            archetypeContext.append("Exemplar Files:\n");
            for (CtxNodeDimension nd : topNodes) {
                archetypeContext.append("  - ").append(nd.getNode().getPath()).append("\n");
            }
            archetypeContext.append("\n");
        }

        if (archetypeContext.isEmpty()) {
            batchLogger.info(
                    "RESEARCH_PATTERNS",
                    "No exemplar files found for any ARCHETYPE dimension. Skipping architecture pattern deduction.");
            return;
        }

        executeArchitecturePatternDeduction(batch, archetypeContext.toString(), batchLogger);
    }

    private void executeArchitecturePatternDeduction(
            IngestionBatch batch, String archetypeContext, IngestionBatchLogService.BatchLogger batchLogger) {

        int maxTurns = 10;
        ReActLoop<ArchitecturePatternResult[]> loop = ReActLoop.structured(
                        chatClientFor(batch), toolCallbacks, ArchitecturePatternResult[].class, this::validatePatterns)
                .maxIterations(maxTurns)
                .executor(agentTaskExecutor)
                .onToolCallCount(count -> usageTracker.recordToolCalls(batch.getId(), count))
                .onThought(thought -> logger.debug("RESEARCH_PATTERNS: {}", thought))
                .onToolEvent((taskId, toolName, phase, detail) -> logToolEvent(batchLogger, toolName, phase, detail));

        String systemPrompt = """
                You are an expert software architect.

                Based on the following archetypes and their exemplar files, deduce the system-wide architecture patterns.
                Use tools **judiciously** to read exemplar files and understand the architectural patterns.
                You have limited turns to complete this research, and limited tool-calls.
                Call tools only when necessary and in parallel when possible.

                Archetypes and Exemplar Files:
                """ + archetypeContext + "\n\n" + loop.format();

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage(
                "Please analyze the archetypes and return the architecture patterns in the required format."));

        ToolContext toolContext = new ToolContext(Map.of("batchId", batch.getId()));

        ArchitecturePatternResult[] patternResults;
        try {
            patternResults = loop.run(history, toolContext);
        } catch (ReActLoopFatalException e) {
            batchLogger.error("RESEARCH_PATTERNS", "Fatal LLM error during pattern deduction: " + e.getMessage());
            throw new RuntimeException("LLM call failed during pattern deduction: " + e.getMessage(), e);
        } catch (ReActLoopExhaustedException e) {
            if (e.lastValidationError() != null) {
                String message = "Failed to get valid architecture patterns after " + maxTurns
                        + " attempts. Last error: " + e.lastValidationError();
                batchLogger.error("RESEARCH_PATTERNS", message);
                throw new RuntimeException(message, e);
            }
            String message = "Agent exceeded " + maxTurns
                    + " turns without returning architecture pattern content. The LLM may need more turns, or"
                    + " was calling tools without ever synthesizing a final answer.";
            batchLogger.error("RESEARCH_PATTERNS", message);
            throw new RuntimeException(
                    "No architecture pattern content returned from LLM after " + maxTurns + " turns.", e);
        } catch (RuntimeException e) {
            batchLogger.error("RESEARCH_PATTERNS", "LLM call failed during pattern deduction: " + e.getMessage());
            throw new RuntimeException("LLM call failed during pattern deduction", e);
        }

        if (patternResults == null) {
            throw new RuntimeException(
                    "No architecture pattern content returned from LLM after " + maxTurns + " turns.");
        }

        List<CtxArchitecturePattern> patterns = new ArrayList<>();
        for (ArchitecturePatternResult pattern : patternResults) {
            List<CtxNode> exemplarNodes = new ArrayList<>();
            if (pattern.exemplarPaths() != null) {
                for (String exemplarPath : pattern.exemplarPaths()) {
                    List<CtxNode> found = ctxNodeRepository.findByBatchIdAndPath(batch.getId(), exemplarPath);
                    exemplarNodes.addAll(found);
                }
            }

            patterns.add(new CtxArchitecturePattern(
                    batch,
                    batch.getRepository().getTeam().getId(),
                    pattern.name(),
                    pattern.description(),
                    exemplarNodes));
        }

        ctxArchitecturePatternRepository.saveAll(patterns);
        logger.debug("RESEARCH_PATTERNS: Saved {} architecture patterns.", patterns.size());
        batchLogger.info("RESEARCH_PATTERNS", "Saved " + patterns.size() + " architecture patterns.");
    }

    private void logToolEvent(
            IngestionBatchLogService.BatchLogger batchLogger,
            String toolName,
            ReActLoop.ToolPhase phase,
            String detail) {
        boolean detailAddsContext = detail != null && !detail.isBlank() && !detail.equals(toolName);
        switch (phase) {
            case START -> {
                logger.debug("RESEARCH_PATTERNS: calling tool {}{}", toolName, detailAddsContext ? " - " + detail : "");
                batchLogger.info(
                        "RESEARCH_PATTERNS", "Calling tool: " + toolName + (detailAddsContext ? " - " + detail : ""));
            }
            case COMPLETE -> logger.debug("RESEARCH_PATTERNS: tool {} completed", toolName);
            case ERROR -> {
                logger.debug("RESEARCH_PATTERNS: tool {} failed: {}", toolName, detail);
                batchLogger.error("RESEARCH_PATTERNS", "Tool execution failed: " + toolName + " - " + detail);
            }
        }
    }

    private void validatePatterns(ArchitecturePatternResult[] patterns) {
        if (patterns == null || patterns.length == 0) {
            throw new IllegalArgumentException("No patterns found in response.");
        }
        for (int i = 0; i < patterns.length; i++) {
            ArchitecturePatternResult p = patterns[i];
            if (p.name() == null || p.name().isBlank()) {
                throw new IllegalArgumentException("Pattern at index " + i + " is missing a 'name'.");
            }
            if (p.description() == null || p.description().isBlank()) {
                throw new IllegalArgumentException("Pattern at index " + i + " is missing a 'description'.");
            }
        }
    }
}
