package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class IngestionBatchRepositoryTest {

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        repository =
                new Repository("kratis-core", "https://github.com/kratisai/kratis.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);
        repositoryRepository.saveAndFlush(repository);
    }

    @Test
    void shouldSaveAndFindIngestionBatch() {
        IngestionBatch batch = new IngestionBatch(repository);
        batch.setStatus(IngestionStatus.QUEUED);
        batch.setCommitHash("abcdef1234567890");
        batch.setErrorMessage("No error");
        batch.setActive(false);

        ingestionBatchRepository.saveAndFlush(batch);

        assertThat(batch.getId()).isNotNull();
        assertThat(batch.getStartedAt()).isNotNull();

        Optional<IngestionBatch> found = ingestionBatchRepository.findById(batch.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRepository().getId()).isEqualTo(repository.getId());
        assertThat(found.get().getStatus()).isEqualTo(IngestionStatus.QUEUED);
        assertThat(found.get().getCommitHash()).isEqualTo("abcdef1234567890");
        assertThat(found.get().getErrorMessage()).isEqualTo("No error");
    }

    @Test
    void shouldFindBatchesByRepositoryId() {
        IngestionBatch batch1 = new IngestionBatch(repository);
        batch1.setStatus(IngestionStatus.SUCCESS);
        batch1.setActive(false);
        ingestionBatchRepository.save(batch1);

        IngestionBatch batch2 = new IngestionBatch(repository);
        batch2.setStatus(IngestionStatus.PROCESSING);
        batch2.setActive(true);
        ingestionBatchRepository.save(batch2);

        ingestionBatchRepository.flush();

        List<IngestionBatch> batches = ingestionBatchRepository.findByRepositoryId(repository.getId());
        assertThat(batches).hasSize(2);
    }

    @Test
    void shouldFindActiveBatch() {
        IngestionBatch batch1 = new IngestionBatch(repository);
        batch1.setStatus(IngestionStatus.SUCCESS);
        batch1.setActive(false);
        ingestionBatchRepository.save(batch1);

        IngestionBatch batch2 = new IngestionBatch(repository);
        batch2.setStatus(IngestionStatus.SUCCESS);
        batch2.setActive(true);
        ingestionBatchRepository.save(batch2);

        ingestionBatchRepository.flush();

        Optional<IngestionBatch> activeBatch =
                ingestionBatchRepository.findByRepositoryIdAndIsActiveTrue(repository.getId());
        assertThat(activeBatch).isPresent();
        assertThat(activeBatch.get().getId()).isEqualTo(batch2.getId());
    }

    @Test
    void shouldDeactivateOtherBatchesAtomically() {
        // Create 3 batches: two active, one inactive (simulating transition)
        IngestionBatch batch1 = new IngestionBatch(repository);
        batch1.setActive(true);
        ingestionBatchRepository.save(batch1);

        IngestionBatch batch2 = new IngestionBatch(repository);
        batch2.setActive(true);
        ingestionBatchRepository.save(batch2);

        IngestionBatch newActiveBatch = new IngestionBatch(repository);
        newActiveBatch.setActive(true);
        ingestionBatchRepository.save(newActiveBatch);

        ingestionBatchRepository.flush();

        // Deactivate all others except newActiveBatch
        ingestionBatchRepository.deactivateOtherBatches(repository.getId(), newActiveBatch.getId());
        ingestionBatchRepository.flush();

        // Refresh entity state from database
        IngestionBatch updated1 =
                ingestionBatchRepository.findById(batch1.getId()).orElseThrow();
        IngestionBatch updated2 =
                ingestionBatchRepository.findById(batch2.getId()).orElseThrow();
        IngestionBatch updatedNew =
                ingestionBatchRepository.findById(newActiveBatch.getId()).orElseThrow();

        assertThat(updated1.isActive()).isFalse();
        assertThat(updated2.isActive()).isFalse();
        assertThat(updatedNew.isActive()).isTrue();
    }
}
