package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionPipelineAbortException;
import com.kratisai.controlplane.ingestion.IngestionUsageTracker;
import com.kratisai.controlplane.ingestion.ReadFileTool;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ResearchDimensionWorker {

    private static final Logger logger = LoggerFactory.getLogger(ResearchDimensionWorker.class);

    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final ReadFileTool readFileTool;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final IngestionProperties ingestionProperties;
    private final IngestionUsageTracker usageTracker;

    public ResearchDimensionWorker(
            ChatModelFactory chatModelFactory,
            LiteLLMProvisioningService litellmProvisioningService,
            ReadFileTool readFileTool,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            IngestionProperties ingestionProperties,
            IngestionUsageTracker usageTracker) {
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
        this.readFileTool = readFileTool;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ingestionProperties = ingestionProperties;
        this.usageTracker = usageTracker;
    }

    // NOT transactional - we mustn't hold onto long-lived transactions in the worker thread.
    // ChatResponse.getMetadata()/Generation.getOutput() are contractually non-null in Spring
    // AI; SpotBugs cannot see those contracts and assumes nullable.
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public DimensionSynopsisResult research(
            ResearchDimensionTask task, IngestionBatchLogService.BatchLogger batchLogger, IngestionBatch batch) {

        CtxDimension dimension = task.dimension();
        StringBuilder fileSnippets = buildFileSnippets(task.batchId(), dimension);

        String promptText = DimensionPrompt.fromCategory(dimension.getCategory())
                .format(
                        dimension.getName(),
                        dimension.getGlobPatterns(),
                        fileSnippets.toString(),
                        new BeanOutputConverter<>(DimensionSynopsisResult.class).getFormat());

        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(task.provider(), task.modelName());
        String virtualKey = batch.getUsage().getVirtualKey();
        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(task.provider(), litellmModelName, virtualKey, false);

        int maxRetries = ingestionProperties.getDimensionResearch().getMaxRetries();
        long initialBackoffMs = ingestionProperties.getDimensionResearch().getInitialBackoffMs();
        long maxBackoffMs = ingestionProperties.getDimensionResearch().getMaxBackoffMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            logger.debug(
                    "{} research for {}: \"{}\"",
                    attempt == 0 ? "Starting" : "Retrying",
                    dimension.getCategory(),
                    dimension.getName());
            batchLogger.info(
                    "RESEARCH_DIMENSIONS",
                    (attempt == 0 ? "Starting" : "Retrying") + " research for " + dimension.getCategory() + ": \""
                            + dimension.getName() + "\"");
            try {
                Prompt prompt =
                        new Prompt(promptText, ToolCallingChatOptions.builder().build());
                ChatResponse response = chatModel.call(prompt);
                if (response.getResult() == null
                        || response.getResult().getOutput().getText() == null) {
                    throw new IngestionPipelineAbortException("LLM returned null result", null);
                }
                String text = response.getResult().getOutput().getText();
                usageTracker.recordTokens(batch.getId(), response.getMetadata().getUsage());

                BeanOutputConverter<DimensionSynopsisResult> converter =
                        new BeanOutputConverter<>(DimensionSynopsisResult.class);
                return converter.convert(text);

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    throw new IngestionPipelineAbortException(
                            "Authentication/Authorization failed for LLM: " + e.getMessage(), e);
                }
                if (e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError()) {
                    if (attempt == maxRetries) {
                        throw new IngestionPipelineAbortException(
                                "Max retries exceeded for LLM request: " + e.getMessage(), e);
                    }
                    long backoff = Math.min(initialBackoffMs * (long) Math.pow(2, attempt), maxBackoffMs);
                    sleep(backoff);
                } else {
                    throw new IngestionPipelineAbortException("Unexpected HTTP client error: " + e.getMessage(), e);
                }
            } catch (ResourceAccessException e) {
                if (attempt == maxRetries) {
                    throw new IngestionPipelineAbortException(
                            "Max retries exceeded for LLM request (ResourceAccessException): " + e.getMessage(), e);
                }
                long backoff = Math.min(initialBackoffMs * (long) Math.pow(2, attempt), maxBackoffMs);
                sleep(backoff);
            }
        }

        throw new IngestionPipelineAbortException(
                "Unexpected exit from retry loop for dimension: " + dimension.getName());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IngestionPipelineAbortException("Thread interrupted during backoff", ie);
        }
    }

    private @NonNull StringBuilder buildFileSnippets(java.util.UUID batchId, CtxDimension dimension) {
        List<CtxNodeDimension> nodeDimensions = ctxNodeDimensionRepository.findByDimensionIdWithNode(dimension.getId());
        // DimensionDiscoveryService only maps dimensions to FILE nodes, so these are all FILE nodes.
        List<CtxNodeDimension> topNodeDimensions = nodeDimensions.stream()
                .filter(nd -> nd.getRankScore() != null)
                .sorted(Comparator.comparing(CtxNodeDimension::getRankScore).reversed())
                .limit(5)
                .toList();

        StringBuilder fileSnippets = new StringBuilder();
        int fileCount = 0;
        for (CtxNodeDimension nd : topNodeDimensions) {
            if (fileCount >= 3) {
                break;
            }
            CtxNode node = nd.getNode();
            if (node.getNodeType() != NodeType.FILE || node.getPath() == null) {
                throw new IllegalStateException("Node is not a file: " + node.getId());
            } else {
                ToolContext toolContext = new ToolContext(Map.of("batchId", batchId));
                var result = readFileTool.readFile(node.getPath(), toolContext);
                if (result.containsKey("content")) {
                    String content = result.get("content");
                    String truncatedContent = content.length() > 2000 ? content.substring(0, 2000) + "..." : content;
                    fileSnippets
                            .append("File: ")
                            .append(node.getPath())
                            .append("\n")
                            .append("Content:\n")
                            .append(truncatedContent)
                            .append("\n\n");
                    fileCount++;
                } else {
                    logger.warn("Failed to read file {}: {}", node.getPath(), result.get("error"));
                }
            }
        }
        logger.debug(
                "Providing {} file snippets for dimension {} [{} chars]",
                fileCount,
                dimension.getName(),
                fileSnippets.length());
        return fileSnippets;
    }
}
