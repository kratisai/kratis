package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manages ingestion queue recovery on application startup.
 *
 * <p>On startup, this service:
 *
 * <ol>
 *   <li>Clears any existing data for orphaned PROCESSING batches
 *   <li>Resets any orphaned PROCESSING batches back to QUEUED
 *   <li>Resubmits all QUEUED batches to the ingestion worker queue
 * </ol>
 */
@Service
public class IngestionQueueManager {

    private static final Logger logger = LoggerFactory.getLogger(IngestionQueueManager.class);

    private final IngestionBatchRepository ingestionBatchRepository;
    private final IngestionWorker ingestionWorker;
    private final TransactionTemplate transactionTemplate;
    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final CtxDimensionRepository ctxDimensionRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final CtxEmbeddingRepository ctxEmbeddingRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxArchitecturePatternRepository ctxArchitecturePatternRepository;
    private final CtxSurfaceRepository ctxSurfaceRepository;
    private final CtxRepoRelationRepository ctxRepoRelationRepository;
    private final CtxRepoSynopsisRepository ctxRepoSynopsisRepository;
    private final IngestionBatchLogRepository ingestionBatchLogRepository;

    public IngestionQueueManager(
            IngestionBatchRepository ingestionBatchRepository,
            IngestionWorker ingestionWorker,
            PlatformTransactionManager transactionManager,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            CtxDimensionRepository ctxDimensionRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            CtxEmbeddingRepository ctxEmbeddingRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            CtxSurfaceRepository ctxSurfaceRepository,
            CtxRepoRelationRepository ctxRepoRelationRepository,
            CtxRepoSynopsisRepository ctxRepoSynopsisRepository,
            IngestionBatchLogRepository ingestionBatchLogRepository) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ingestionWorker = ingestionWorker;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ctxEmbeddingRepository = ctxEmbeddingRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
        this.ctxRepoRelationRepository = ctxRepoRelationRepository;
        this.ctxRepoSynopsisRepository = ctxRepoSynopsisRepository;
        this.ingestionBatchLogRepository = ingestionBatchLogRepository;
    }

    /**
     * Recover and resubmit queued batches on application startup.
     *
     * <p>This method is called once when the application is ready. Each orphaned PROCESSING batch is
     * cleared and reset in its own transaction. A batch that cannot be cleared is marked FAILED, so
     * one bad batch cannot stop the application from starting or block recovery of the others.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAndResubmit() {
        List<UUID> processingBatchIds =
                transactionTemplate.execute(status -> ingestionBatchRepository.findProcessingBatchIds());
        for (UUID batchId : processingBatchIds) {
            recoverBatch(batchId);
        }

        // Read QUEUED batches after every reset has committed.
        List<UUID> queuedBatchIds = transactionTemplate.execute(status -> {
            List<IngestionBatch> queuedBatches = ingestionBatchRepository.findByStatus(IngestionStatus.QUEUED);
            return queuedBatches.stream().map(IngestionBatch::getId).toList();
        });

        // Submit batches to async worker (outside transaction)
        for (UUID batchId : queuedBatchIds) {
            logger.info("Resubmitting queued batch {} to ingestion queue", batchId);
            ingestionWorker.runIngestion(batchId);
        }

        if (!queuedBatchIds.isEmpty()) {
            logger.info("Resubmitted {} queued batches to ingestion queue", queuedBatchIds.size());
        } else {
            logger.info("No queued batches to resubmit on startup");
        }
    }

    private void recoverBatch(UUID batchId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                logger.info("Clearing data for orphaned PROCESSING batch {}", batchId);
                // Bulk deletes run immediately in FK-safe order: scheduled per-entity removals would
                // interact with the node delete's DB cascade and the context-clearing reset update.
                ctxEmbeddingRepository.deleteByBatchId(batchId);
                ctxArchitecturePatternRepository.deleteByBatchId(batchId);
                ctxNodeDimensionRepository.deleteByBatchId(batchId);
                ctxDimensionRepository.deleteByBatchId(batchId);
                ctxEdgeRepository.deleteByBatchId(batchId);
                ctxSurfaceRepository.deleteByBatchId(batchId);
                ctxRepoRelationRepository.deleteByBatchId(batchId);
                ctxRepoSynopsisRepository.deleteByBatchId(batchId);
                ctxWikiPageRepository.deleteByBatchId(batchId);
                ctxNodeRepository.deleteByBatchId(batchId);
                ingestionBatchLogRepository.deleteByBatchId(batchId);
                if (ingestionBatchRepository.resetOrphanedProcessingBatch(batchId) > 0) {
                    logger.info("Reset orphaned PROCESSING batch {} to QUEUED", batchId);
                }
            });
        } catch (RuntimeException recoveryFailure) {
            logger.error("Failed to recover orphaned PROCESSING batch {}; marking it FAILED", batchId, recoveryFailure);
            markRecoveryFailed(batchId, recoveryFailure);
        }
    }

    private void markRecoveryFailed(UUID batchId, RuntimeException recoveryFailure) {
        try {
            transactionTemplate.executeWithoutResult(status -> ingestionBatchRepository.failOrphanedProcessingBatch(
                    batchId, "Startup recovery failed: " + recoveryFailure.getMessage(), Instant.now()));
        } catch (RuntimeException markFailure) {
            recoveryFailure.addSuppressed(markFailure);
            logger.error("Could not mark orphaned batch {} FAILED; it remains PROCESSING", batchId, markFailure);
        }
    }
}
