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
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
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
import java.util.HashSet;
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
            IngestionUsageTracker usageTracker,
            ReadFileTool readFileTool,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
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
        String virtualKey = batch.modelUsageFor(ModelKind.CHAT)
                .map(IngestionModelUsage::getVirtualKey)
                .orElse(null);
        return ChatClient.builder(
                        chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false))
                .build();
    }

    @Transactional
    public String researchPatterns(IngestionBatch batch, IngestionBatchLogService.BatchLogger batchLogger) {
        List<CtxDimension> archetypeDimensions = ctxDimensionRepository.findByBatchId(batch.getId()).stream()
                .filter(d -> d.getCategory() == DimensionCategory.ARCHETYPE)
                .toList();

        if (archetypeDimensions.isEmpty()) {
            return "skipped (no ARCHETYPE dimensions found)";
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
            return "skipped (no exemplar files found for ARCHETYPE dimensions)";
        }

        int saved = executeArchitecturePatternDeduction(batch, archetypeContext.toString(), batchLogger);
        return "saved " + saved + " architecture patterns";
    }

    private int executeArchitecturePatternDeduction(
            IngestionBatch batch, String archetypeContext, IngestionBatchLogService.BatchLogger batchLogger) {

        int maxTurns = 10;
        ReActLoop<ArchitecturePatternResult[]> loop = ReActLoop.structured(
                        chatClientFor(batch), toolCallbacks, ArchitecturePatternResult[].class, this::validatePatterns)
                .maxIterations(maxTurns)
                .executor(agentTaskExecutor)
                .onToolCallCount(count -> usageTracker.recordToolCalls(batch.getId(), count))
                .onThought((attempt, thought) -> logger.debug("RESEARCH_PATTERNS: {}", thought))
                .onToolEvent((attempt, taskId, toolName, phase, detail) ->
                        logToolEvent(batchLogger, toolName, phase, detail));

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
            // Deduplicate by node id: the LLM may list the same exemplar file twice, and the
            // join table enforces a (pattern_id, node_id) primary key.
            List<CtxNode> exemplarNodes = new ArrayList<>();
            Set<UUID> seenNodeIds = new HashSet<>();
            if (pattern.exemplarPaths() != null) {
                for (String exemplarPath : pattern.exemplarPaths()) {
                    for (CtxNode node : ctxNodeRepository.findByBatchIdAndPath(batch.getId(), exemplarPath)) {
                        if (seenNodeIds.add(node.getId())) {
                            exemplarNodes.add(node);
                        }
                    }
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
        return patterns.size();
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
                batchLogger.debug(
                        "RESEARCH_PATTERNS", "Calling tool: " + toolName + (detailAddsContext ? " - " + detail : ""));
            }
            case COMPLETE -> logger.debug("RESEARCH_PATTERNS: tool {} completed", toolName);
            case ERROR -> {
                logger.debug("RESEARCH_PATTERNS: tool {} failed: {}", toolName, detail);
                batchLogger.warn("RESEARCH_PATTERNS", "Tool execution failed (retrying): " + toolName + " - " + detail);
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
