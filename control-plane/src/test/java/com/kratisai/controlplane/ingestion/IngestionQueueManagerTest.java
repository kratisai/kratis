package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
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
    void resetOrphanedProcessingBatches_shouldResetProcessingToQueued() {
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

        // Run the reset query directly
        int resetCount = ingestionBatchRepository.resetOrphanedProcessingBatches();

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
    void resetOrphanedProcessingBatches_shouldReturnZeroWhenNoProcessingBatches() {
        // Create only QUEUED batches
        IngestionBatch queuedBatch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(queuedBatch);

        // Run the reset query
        int resetCount = ingestionBatchRepository.resetOrphanedProcessingBatches();

        // Verify no batches were reset
        assertThat(resetCount).isEqualTo(0);
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
