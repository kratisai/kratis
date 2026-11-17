package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchLogRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
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

/** Recovers orphaned PROCESSING and QUEUED TeamWikiBatch records on application startup. */
@Service
public class TeamWikiQueueManager {

    private static final Logger logger = LoggerFactory.getLogger(TeamWikiQueueManager.class);

    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final TeamWikiWorker teamWikiWorker;
    private final CtxEmbeddingRepository ctxEmbeddingRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final TeamWikiBatchLogRepository batchLogRepository;
    private final TransactionTemplate transactionTemplate;

    public TeamWikiQueueManager(
            TeamWikiBatchRepository teamWikiBatchRepository,
            TeamWikiWorker teamWikiWorker,
            CtxEmbeddingRepository ctxEmbeddingRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            TeamWikiBatchLogRepository batchLogRepository,
            PlatformTransactionManager transactionManager) {
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.teamWikiWorker = teamWikiWorker;
        this.ctxEmbeddingRepository = ctxEmbeddingRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.batchLogRepository = batchLogRepository;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = tt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAndResubmit() {
        transactionTemplate.executeWithoutResult(status -> {
            List<UUID> processingIds = teamWikiBatchRepository.findProcessingBatchIds();
            for (UUID batchId : processingIds) {
                logger.info("Clearing partial data for orphaned PROCESSING team wiki batch {}", batchId);
                ctxEmbeddingRepository.deleteByTeamWikiBatchId(batchId);
                ctxWikiPageRepository.deleteByTeamWikiBatchId(batchId);
                batchLogRepository.deleteByTeamWikiBatchId(batchId);
            }
            int resetCount = teamWikiBatchRepository.resetOrphanedProcessingBatches();
            if (resetCount > 0) {
                logger.info("Reset {} orphaned PROCESSING team wiki batches to QUEUED", resetCount);
            }
        });

        List<UUID> queuedIds = transactionTemplate.execute(
                status -> teamWikiBatchRepository.findByStatus(IngestionStatus.QUEUED).stream()
                        .map(TeamWikiBatch::getId)
                        .toList());
        if (queuedIds.isEmpty()) {
            return;
        }
        for (UUID batchId : queuedIds) {
            logger.info("Resubmitting queued team wiki batch {}", batchId);
            teamWikiWorker.runBuild(batchId);
        }
    }
}
