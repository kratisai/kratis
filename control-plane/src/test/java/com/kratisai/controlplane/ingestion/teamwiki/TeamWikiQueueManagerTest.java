package com.kratisai.controlplane.ingestion.teamwiki;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchLogRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class TeamWikiQueueManagerTest {

    @Mock
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Mock
    private TeamWikiWorker teamWikiWorker;

    @Mock
    private CtxEmbeddingRepository ctxEmbeddingRepository;

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Mock
    private TeamWikiBatchLogRepository batchLogRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TeamWikiQueueManager manager;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        manager = new TeamWikiQueueManager(
                teamWikiBatchRepository,
                teamWikiWorker,
                ctxEmbeddingRepository,
                ctxWikiPageRepository,
                batchLogRepository,
                transactionManager);
    }

    @Test
    void recoverAndResubmit_clearsOrphanedProcessingAndResubmitsQueued() {
        UUID orphanedId = UUID.randomUUID();
        UUID queuedId = UUID.randomUUID();
        TeamWikiBatch queuedBatch = new TeamWikiBatch();
        queuedBatch.setId(queuedId);

        when(teamWikiBatchRepository.findProcessingBatchIds()).thenReturn(List.of(orphanedId));
        when(teamWikiBatchRepository.resetOrphanedProcessingBatches()).thenReturn(1);
        when(teamWikiBatchRepository.findByStatus(IngestionStatus.QUEUED)).thenReturn(List.of(queuedBatch));

        manager.recoverAndResubmit();

        verify(ctxEmbeddingRepository).deleteByTeamWikiBatchId(orphanedId);
        verify(ctxWikiPageRepository).deleteByTeamWikiBatchId(orphanedId);
        verify(batchLogRepository).deleteByTeamWikiBatchId(orphanedId);
        verify(teamWikiBatchRepository).resetOrphanedProcessingBatches();
        verify(teamWikiWorker).runBuild(queuedId);
    }
}
