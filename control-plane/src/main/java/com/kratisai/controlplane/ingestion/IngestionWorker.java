package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.api.wsdto.IngestionStatusEvent;
import com.kratisai.controlplane.ingestion.parse.CodebaseMemoryParserService;
import com.kratisai.controlplane.ingestion.parse.DependencyLinkerService;
import com.kratisai.controlplane.ingestion.parse.DimensionDiscoveryService;
import com.kratisai.controlplane.ingestion.research.DimensionResearchService;
import com.kratisai.controlplane.ingestion.research.PatternResearchService;
import com.kratisai.controlplane.ingestion.research.SubgraphRankingService;
import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.ingestion.write.WikiGenerationService;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsage;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.VirtualKeyService;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IngestionWorker {

    private static final Logger logger = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionBatchRepository ingestionBatchRepository;
    private final GitCloneService gitCloneService;
    private final CodebaseMemoryParserService codebaseMemoryParserService;
    private final DependencyLinkerService dependencyLinkerService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final IngestionBatchLogService ingestionBatchLogService;
    private final DimensionResearchService dimensionResearchService;
    private final WikiGenerationService wikiGenerationService;
    private final SemanticIndexingService semanticIndexingService;
    private final DimensionDiscoveryService dimensionDiscoveryService;
    private final SubgraphRankingService subgraphRankingService;
    private final PatternResearchService patternResearchService;
    private final VirtualKeyService virtualKeyService;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final IngestionUsageTracker usageTracker;

    public IngestionWorker(
            IngestionBatchRepository ingestionBatchRepository,
            GitCloneService gitCloneService,
            CodebaseMemoryParserService codebaseMemoryParserService,
            DependencyLinkerService dependencyLinkerService,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            IngestionBatchLogService ingestionBatchLogService,
            DimensionResearchService dimensionResearchService,
            WikiGenerationService wikiGenerationService,
            SemanticIndexingService semanticIndexingService,
            DimensionDiscoveryService dimensionDiscoveryService,
            SubgraphRankingService subgraphRankingService,
            PatternResearchService patternResearchService,
            VirtualKeyService virtualKeyService,
            LiteLLMProvisioningService litellmProvisioningService,
            IngestionUsageTracker usageTracker) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.gitCloneService = gitCloneService;
        this.codebaseMemoryParserService = codebaseMemoryParserService;
        this.dependencyLinkerService = dependencyLinkerService;
        this.eventPublisher = eventPublisher;
        this.ingestionBatchLogService = ingestionBatchLogService;
        this.dimensionResearchService = dimensionResearchService;

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = tt;
        this.wikiGenerationService = wikiGenerationService;
        this.semanticIndexingService = semanticIndexingService;
        this.dimensionDiscoveryService = dimensionDiscoveryService;
        this.subgraphRankingService = subgraphRankingService;
        this.patternResearchService = patternResearchService;
        this.virtualKeyService = virtualKeyService;
        this.litellmProvisioningService = litellmProvisioningService;
        this.usageTracker = usageTracker;
    }

    /**
     * Executes the actual ingestion work Async. Uses phase-level transactions to ensure that if a
     * failure occurs, all previously completed phases (nodes, edges, archetypes, patterns)
     * are retained in the database, and the batch is marked as FAILED.
     */
    @Async("repoIngestTaskExecutor")
    public void runIngestion(UUID batchId) {
        logger.info("Starting asynchronous ingestion for batch {}", batchId);

        // Transaction 1: Set PROCESSING status and publish event
        var batchLogger = transactionTemplate.execute(status -> markBatchStarted(batchId));

        GitCloneService.CloneResult cloneResult = null;
        try {
            transactionTemplate.execute(status -> generateBatchVirtualKey(batchId));

            // Perform ingestion with phase-level transactions
            cloneResult = performIngestion(batchId, batchLogger);
        } catch (Throwable e) {
            // Catch Throwable (not just Exception) so Errors such as GraalVM's
            // UnsupportedFeatureError still abort the pipeline and mark the batch FAILED.
            logger.error("Ingestion batch {} failed during execution", batchId, e);
            transactionTemplate.execute(txStatus -> markBatchFailed(batchId, e, batchLogger));
        } finally {
            // Clean up cloned repository directory to prevent disk leakage in Step 1
            if (cloneResult != null && cloneResult.cloneDirectory() != null) {
                try {
                    gitCloneService.cleanupDirectory(cloneResult.cloneDirectory());
                    logger.info("Cleaned up temporary clone directory: {}", cloneResult.cloneDirectory());
                } catch (IOException ex) {
                    logger.warn("Failed to cleanup clone directory: {}", cloneResult.cloneDirectory(), ex);
                }
            }

            // Revoke virtual key and refresh usage after SUCCESS or FAILED
            transactionTemplate.execute(status -> finalizeBatchUsageAndRevokeKey(batchId));
        }

        // Publish final status event
        transactionTemplate.execute(txStatus -> {
            IngestionBatch batch = loadBatch(batchId);
            eventPublisher.publishEvent(new IngestionStatusEvent(batch));
            return null;
        });
    }

    public IngestionBatchLogService.@NonNull BatchLogger markBatchStarted(UUID batchId) {
        IngestionBatch batch = loadBatch(batchId);
        batch.setStatus(IngestionStatus.PROCESSING);
        ingestionBatchRepository.saveAndFlush(batch);
        eventPublisher.publishEvent(new IngestionStatusEvent(batch));

        var loggerInstance = new IngestionBatchLogService.BatchLogger(
                batchId, batch.getRepository().getTeam().getId(), ingestionBatchLogService);
        loggerInstance.info("START", "Starting asynchronous repository ingestion process.");
        return loggerInstance;
    }

    public GitCloneService.@NonNull CloneResult performIngestion(
            UUID batchId, IngestionBatchLogService.BatchLogger batchLogger) {

        GitCloneService.CloneResult cloneResult = executePhase(
                batchId,
                batchLogger,
                "CLONE",
                "Cloning repository from remote location.",
                "Cloning completed successfully.",
                batch -> {
                    try {
                        return gitCloneService.cloneRepository(batch.getRepository(), batch.getId(), batchLogger);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException("Failed to clone repository: " + e.getMessage(), e);
                    }
                });

        executePhase(
                batchId,
                batchLogger,
                "PREPARE_AST",
                "Parsing repository structure using codebase-memory.",
                "Code parsing completed successfully.",
                batch -> {
                    codebaseMemoryParserService.prepareAst(batch, cloneResult.cloneDirectory());
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "PREPARE_LINKS",
                "Resolving structural and cross-repository symbol dependencies.",
                "Symbol linking completed successfully.",
                batch -> {
                    dependencyLinkerService.prepareLinks(batch);
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "PREPARE_DIMENSIONS",
                "Starting Domain, Archetype + Utility discovery.",
                "Domain, Archetype + Utility discovery completed successfully.",
                batch -> {
                    dimensionDiscoveryService.discoverDimensions(batch);
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "RESEARCH_GRAPH",
                "Calculating subgraph PageRank for dimension nodes.",
                "Subgraph ranking completed successfully.",
                batch -> {
                    subgraphRankingService.rankDimensions(batch);
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "RESEARCH_DIMENSIONS",
                "Researching dimensions and generating synopses.",
                "Dimension research completed successfully.",
                batch -> {
                    dimensionResearchService.researchDimensions(batch);
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "RESEARCH_PATTERNS",
                "Deducing system-wide architecture patterns from dimension hubs.",
                "Architecture pattern deduction completed successfully.",
                batch -> {
                    patternResearchService.researchPatterns(batch);
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "GENERATE_WIKI",
                "Generating repository Wiki documentation.",
                "Wiki generation completed successfully.",
                batch -> {
                    wikiGenerationService.generateWiki(batch);
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "GENERATE_INDEX",
                "Generating vector embeddings for wiki pages.",
                "Vector embeddings generated successfully.",
                batch -> {
                    semanticIndexingService.indexBatch(
                            batch, batch.getRepository().getTeam());
                    return null;
                });

        executePhase(
                batchId,
                batchLogger,
                "SUCCESS",
                "Finalizing ingestion batch.",
                "Repository ingestion and intelligence extraction completed successfully!",
                batch -> {
                    batch.setStatus(IngestionStatus.SUCCESS);
                    batch.setCommitHash(cloneResult.commitHash());
                    batch.setCompletedAt(Instant.now());
                    batch.setActive(true);
                    ingestionBatchRepository.saveAndFlush(batch);

                    ingestionBatchRepository.deactivateOtherBatches(
                            batch.getRepository().getId(), batch.getId());

                    IngestionWorker.logger.info(
                            "Ingestion batch {} succeeded. Commit hash: {}. Atomic active swap completed.",
                            batchId,
                            cloneResult.commitHash());
                    return null;
                });

        return cloneResult;
    }

    private <T> T executePhase(
            UUID batchId,
            IngestionBatchLogService.BatchLogger batchLogger,
            String phaseName,
            String startMessage,
            String successMessage,
            java.util.function.Function<IngestionBatch, T> phaseAction) {
        return transactionTemplate.execute(status -> {
            IngestionBatch batch = loadBatch(batchId);
            batchLogger.info(phaseName, startMessage);
            logger.debug("{}: {}", phaseName, startMessage);
            T result = phaseAction.apply(batch);
            batchLogger.info(phaseName, successMessage);
            logger.debug("{}: {}", phaseName, successMessage);
            return result;
        });
    }

    public @Nullable Object markBatchFailed(
            UUID batchId, Throwable e, IngestionBatchLogService.BatchLogger batchLogger) {
        IngestionBatch batch = loadBatch(batchId);
        batch.setStatus(IngestionStatus.FAILED);
        batch.setErrorMessage(e.getMessage());
        batch.setCompletedAt(Instant.now());
        batch.setActive(false);
        ingestionBatchRepository.saveAndFlush(batch);
        eventPublisher.publishEvent(new IngestionStatusEvent(batch));
        batchLogger.error("FAILED", "Ingestion process failed: " + e.getMessage());
        return null;
    }

    public @NonNull IngestionBatch loadBatch(UUID batchId) {
        return ingestionBatchRepository
                .findByIdWithRepositoryAndTeam(batchId)
                .orElseThrow(() -> new IllegalStateException("Ingestion batch not found: " + batchId));
    }

    private Object generateBatchVirtualKey(UUID batchId) {
        IngestionBatch batch = loadBatch(batchId);
        var team = batch.getRepository().getTeam();
        var chatProvider = team.getIngestionProvider();
        var chatModel = team.getIngestionModel();

        if (chatProvider == null || chatModel == null || chatModel.isBlank()) {
            throw new IllegalStateException(
                    "Team {} has no ingestion provider/model.  Models are required for ingestion.");
        }

        var embeddingProvider = team.getEmbeddingProvider();
        var embeddingModel = team.getEmbeddingModel();
        if (embeddingProvider == null || embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException(
                    "Team {} has no embedding provider/model configured; embedding model is required for ingestion.");
        }

        List<String> grantedModels = new ArrayList<>();
        grantedModels.add(litellmProvisioningService.buildLiteLLMModelName(chatProvider, chatModel));
        grantedModels.add(litellmProvisioningService.buildLiteLLMModelName(embeddingProvider, embeddingModel));

        String keyAlias = litellmProvisioningService.buildVirtualKeyAlias(
                LiteLLMProvisioningService.VirtualKeyScope.INGESTION, batchId);
        String token = virtualKeyService.generateKey(keyAlias, grantedModels);

        batch.setUsage(LlmUsage.withKey(token));
        ingestionBatchRepository.saveAndFlush(batch);

        logger.info("Generated virtual key for ingestion batch {}", batchId);
        return null;
    }

    private Object finalizeBatchUsageAndRevokeKey(UUID batchId) {
        IngestionBatch batch = loadBatch(batchId);
        String token = batch.getUsage().getVirtualKey();

        if (token != null) {
            try {
                var litellmSnapshot = virtualKeyService.fetchUsage(token);
                var tracked = usageTracker.snapshotAndClear(batchId);
                LlmUsageSnapshot mergedSnapshot;
                if (tracked.isPresent()) {
                    var counters = tracked.get();
                    mergedSnapshot = new LlmUsageSnapshot(
                            litellmSnapshot.spend(),
                            counters.totalTokens(),
                            counters.promptTokens(),
                            counters.completionTokens());
                    batch.setTotalToolCalls(counters.toolCalls());
                } else {
                    mergedSnapshot = litellmSnapshot;
                }
                batch.getUsage().apply(mergedSnapshot);
                ingestionBatchRepository.saveAndFlush(batch);
                logger.info(
                        "Refreshed usage for ingestion batch {}: tokens={}, spend={}, toolCalls={}",
                        batchId,
                        mergedSnapshot.totalTokens(),
                        mergedSnapshot.spend(),
                        batch.getTotalToolCalls());
            } catch (Exception e) {
                logger.warn("Failed to refresh final usage for batch {}: {}", batchId, e.getMessage());
            }

            try {
                virtualKeyService.revokeKey(token);
                logger.info("Revoked virtual key for ingestion batch {}", batchId);
            } catch (Exception e) {
                logger.warn("Failed to revoke virtual key for batch {}: {}", batchId, e.getMessage());
            }
        }

        return null;
    }
}
