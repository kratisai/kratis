package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
class IngestionQueueManagerTest {

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        repository = new Repository(
                "queue-test-repo", "https://github.com/kratisai/queue-test.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);
        repositoryRepository.saveAndFlush(repository);
    }

    @Test
    @Transactional
    void resetOrphanedProcessingBatch_shouldResetProcessingToQueued() {
        // Create an orphaned PROCESSING batch
        IngestionBatch orphanedBatch = new IngestionBatch(repository);
        orphanedBatch.setStatus(IngestionStatus.PROCESSING);
        orphanedBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(orphanedBatch);

        // Create a QUEUED batch
        IngestionBatch queuedBatch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(queuedBatch);

        // Verify initial state
        assertThat(ingestionBatchRepository.findByStatus(IngestionStatus.PROCESSING))
                .hasSize(1);
        assertThat(ingestionBatchRepository.findByStatus(IngestionStatus.QUEUED))
                .hasSize(1);

        int resetCount = ingestionBatchRepository.resetOrphanedProcessingBatch(orphanedBatch.getId());

        // Verify one batch was reset
        assertThat(resetCount).isEqualTo(1);

        // Verify orphaned batch was reset to QUEUED
        List<IngestionBatch> queuedBatches = ingestionBatchRepository.findByStatus(IngestionStatus.QUEUED);
        assertThat(queuedBatches).hasSize(2);

        // Verify the reset batch has the error message
        IngestionBatch resetBatch =
                ingestionBatchRepository.findById(orphanedBatch.getId()).orElseThrow();
        assertThat(resetBatch.getStatus()).isEqualTo(IngestionStatus.QUEUED);
        assertThat(resetBatch.getErrorMessage()).contains("Reset on startup");
    }

    @Test
    @Transactional
    void resetOrphanedProcessingBatch_shouldReturnZeroWhenBatchIsNotProcessing() {
        // Create only QUEUED batches
        IngestionBatch queuedBatch = new IngestionBatch(repository);
        queuedBatch = ingestionBatchRepository.saveAndFlush(queuedBatch);

        int resetCount = ingestionBatchRepository.resetOrphanedProcessingBatch(queuedBatch.getId());

        assertThat(resetCount).isEqualTo(0);
    }

    @Test
    @Transactional
    void failOrphanedProcessingBatch_shouldMarkProcessingBatchFailed() {
        IngestionBatch orphanedBatch = new IngestionBatch(repository);
        orphanedBatch.setStatus(IngestionStatus.PROCESSING);
        orphanedBatch.setActive(true);
        orphanedBatch = ingestionBatchRepository.saveAndFlush(orphanedBatch);

        int failedCount = ingestionBatchRepository.failOrphanedProcessingBatch(
                orphanedBatch.getId(), "Startup recovery failed: boom", Instant.parse("2026-11-18T00:00:00Z"));

        assertThat(failedCount).isEqualTo(1);
        IngestionBatch failedBatch =
                ingestionBatchRepository.findById(orphanedBatch.getId()).orElseThrow();
        assertThat(failedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(failedBatch.getErrorMessage()).contains("Startup recovery failed");
        assertThat(failedBatch.getCompletedAt()).isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
        assertThat(failedBatch.isActive()).isFalse();
    }

    @Test
    @Transactional
    void clearBatchData_shouldDeleteAllAssociatedRecords() {
        // Create a PROCESSING batch
        IngestionBatch batch = new IngestionBatch(repository);
        batch.setStatus(IngestionStatus.PROCESSING);
        ingestionBatchRepository.saveAndFlush(batch);

        // Create associated records
        CtxNode node =
                new CtxNode(batch, repository.getTeam().getId(), repository.getName(), NodeType.CLASS, "src/Main.java");
        ctxNodeRepository.saveAndFlush(node);

        // Verify records exist
        assertThat(ctxNodeRepository.findByBatchId(batch.getId())).hasSize(1);

        // Clear batch data using the repository method
        ctxNodeRepository.deleteByBatchId(batch.getId());

        // Verify records are deleted
        assertThat(ctxNodeRepository.findByBatchId(batch.getId())).isEmpty();
    }
}
