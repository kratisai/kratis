package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.repository.*;
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
    private final CtxEmbeddingRepository ctxEmbeddingRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxArchitecturePatternRepository ctxArchitecturePatternRepository;
    private final IngestionBatchLogRepository ingestionBatchLogRepository;

    public IngestionQueueManager(
            IngestionBatchRepository ingestionBatchRepository,
            IngestionWorker ingestionWorker,
            PlatformTransactionManager transactionManager,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            CtxEmbeddingRepository ctxEmbeddingRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            IngestionBatchLogRepository ingestionBatchLogRepository) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ingestionWorker = ingestionWorker;
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.ctxEmbeddingRepository = ctxEmbeddingRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.ingestionBatchLogRepository = ingestionBatchLogRepository;

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = tt;
    }

    /**
     * Recover and resubmit queued batches on application startup.
     *
     * <p>This method is called once when the application is ready. It resets any orphaned
     * PROCESSING batches to QUEUED, then submits all QUEUED batches to the async worker.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAndResubmit() {
        // Transaction 1: Clear data and reset orphaned PROCESSING batches to QUEUED
        transactionTemplate.execute(status -> {
            List<UUID> processingBatchIds = ingestionBatchRepository.findProcessingBatchIds();
            for (UUID batchId : processingBatchIds) {
                logger.info("Clearing data for orphaned PROCESSING batch {}", batchId);
                // Delete in reverse order of dependencies to respect foreign key constraints
                ctxEmbeddingRepository.deleteByBatchId(batchId);
                ctxArchitecturePatternRepository.deleteByBatchId(batchId);
                ctxEdgeRepository.deleteByBatchId(batchId);
                ctxWikiPageRepository.deleteByBatchId(batchId);
                ctxNodeRepository.deleteByBatchId(batchId);
                ingestionBatchLogRepository.deleteByBatchId(batchId);
            }
            int resetCount = ingestionBatchRepository.resetOrphanedProcessingBatches();
            if (resetCount > 0) {
                logger.info("Reset {} orphaned PROCESSING batches to QUEUED", resetCount);
            }
            return null;
        });

        // Transaction 2: Find all QUEUED batches (after reset committed)
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
}
