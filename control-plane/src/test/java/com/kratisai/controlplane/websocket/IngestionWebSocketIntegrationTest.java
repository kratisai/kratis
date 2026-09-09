package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.IngestionStatusEvent;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class IngestionWebSocketIntegrationTest {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private UUID repoId;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        var repo = new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repo.setTeam(team);
        repositoryRepository.save(repo);
        repoId = repo.getId();
    }

    @Test
    void publishStatusEvent_shouldNotThrowWhenNoSessionsConnected() {
        // Verify publisher works even when no WebSocket sessions are connected
        var repo = repositoryRepository.findById(repoId).orElseThrow();
        var batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.setCommitHash("abc123");
        batch.setCompletedAt(Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        // Commit the transaction so the AFTER_COMMIT listener fires
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // Publish event - should not throw even with no connected sessions
        applicationEventPublisher.publishEvent(new IngestionStatusEvent(batch));

        // Verify batch was saved correctly
        var savedBatch = ingestionBatchRepository.findById(batch.getId()).orElseThrow();
        assertThat(savedBatch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(savedBatch.getCommitHash()).isEqualTo("abc123");
    }

    @Test
    void publishStatusEvent_shouldHandleProcessingStatus() {
        var repo = repositoryRepository.findById(repoId).orElseThrow();
        var batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.PROCESSING);
        ingestionBatchRepository.saveAndFlush(batch);

        // Commit the transaction so the AFTER_COMMIT listener fires
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // Should not throw
        applicationEventPublisher.publishEvent(new IngestionStatusEvent(batch));

        var savedBatch = ingestionBatchRepository.findById(batch.getId()).orElseThrow();
        assertThat(savedBatch.getStatus()).isEqualTo(IngestionStatus.PROCESSING);
    }

    @Test
    void publishStatusEvent_shouldHandleFailedStatus() {
        var repo = repositoryRepository.findById(repoId).orElseThrow();
        var batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.FAILED);
        batch.setErrorMessage("Clone failed");
        batch.setCompletedAt(Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        // Commit the transaction so the AFTER_COMMIT listener fires
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // Should not throw
        applicationEventPublisher.publishEvent(new IngestionStatusEvent(batch));

        var savedBatch = ingestionBatchRepository.findById(batch.getId()).orElseThrow();
        assertThat(savedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(savedBatch.getErrorMessage()).isEqualTo("Clone failed");
    }
}
