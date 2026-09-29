package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.*;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for concurrent ingestion queue behavior.
 *
 * <p>
 * These tests validate that when the concurrency limit is set to 1, multiple
 * simultaneous ingestion requests result in one processing and the rest
 * queued with correct positions.
 *
 * <p>
 * NOTE: @Transactional is NOT used because the async worker thread needs to see
 * committed data. The max-concurrent=1 setting comes from the test
 * application.properties default, which allows this class to share the main
 * Spring context (avoiding a separate Liquibase cycle). Each test cleans up its
 * data in @BeforeEach.
 */
@SpringIntegrationTest
class IngestionConcurrencyIntegrationTest {

    @Autowired
    private IngestionCoordinatorService ingestionCoordinatorService;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private FakeChatModel fakeChatModel;

    private final List<Repository> repositories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        Mockito.reset(processExecutor);

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        // Create 3 repositories for testing
        for (int i = 1; i <= 3; i++) {
            Repository repo = new Repository(
                    "test-repo-" + i, TestDataFactory.getOrCreateDummyRepo(), "main", RepositoryType.GENERIC);
            repo.setTeam(team);
            repositoryRepository.saveAndFlush(repo);
            repositories.add(repo);
        }
    }

    @Test
    void singleConcurrentIngestion_firstProcesses_othersQueueWithCorrectPositions() throws Exception {
        CountDownLatch cloneStarted = new CountDownLatch(1);
        CountDownLatch blockExecution = blockProcessExecutorUntilReleased(cloneStarted);

        // Trigger ingestions on all 3 repositories
        IngestionBatch batch1 =
                ingestionCoordinatorService.triggerIngestion(repositories.get(0).getId());
        IngestionBatch batch2 =
                ingestionCoordinatorService.triggerIngestion(repositories.get(1).getId());
        IngestionBatch batch3 =
                ingestionCoordinatorService.triggerIngestion(repositories.get(2).getId());

        // Wait for the first ingestion to start processing
        assertThat(cloneStarted.await(30, TimeUnit.SECONDS)).isTrue();

        // Verify batch states: first should be PROCESSING, others QUEUED
        IngestionStatus status1 =
                ingestionBatchRepository.findById(batch1.getId()).orElseThrow().getStatus();
        IngestionStatus status2 =
                ingestionBatchRepository.findById(batch2.getId()).orElseThrow().getStatus();
        IngestionStatus status3 =
                ingestionBatchRepository.findById(batch3.getId()).orElseThrow().getStatus();

        assertThat(status1).isEqualTo(IngestionStatus.PROCESSING);
        assertThat(status2).isEqualTo(IngestionStatus.QUEUED);
        assertThat(status3).isEqualTo(IngestionStatus.QUEUED);

        // Verify queue positions
        int position2 = ingestionBatchRepository.countQueuedBatchesBefore(batch2.getStartedAt(), batch2.getId());
        int position3 = ingestionBatchRepository.countQueuedBatchesBefore(batch3.getStartedAt(), batch3.getId());

        // Batch 2 should be position 1 (no queued batches before it)
        assertThat(position2 + 1).isEqualTo(1);
        // Batch 3 should be position 2 (batch 2 is queued before it)
        assertThat(position3 + 1).isEqualTo(2);

        blockExecution.countDown();

        // Wait for first ingestion to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch1 =
                            ingestionBatchRepository.findById(batch1.getId()).orElseThrow();
                    assertThat(updatedBatch1.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });

        // Second ingestion should now be processing
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch2 =
                            ingestionBatchRepository.findById(batch2.getId()).orElseThrow();
                    assertThat(updatedBatch2.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });

        // Third ingestion should also complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch3 =
                            ingestionBatchRepository.findById(batch3.getId()).orElseThrow();
                    assertThat(updatedBatch3.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });
    }

    @Test
    void queuePositionUpdatesWhenBatchAheadCompletes() throws Exception {
        CountDownLatch cloneStarted = new CountDownLatch(1);
        CountDownLatch blockExecution = blockProcessExecutorUntilReleased(cloneStarted);

        // Trigger 3 ingestions
        IngestionBatch batch1 =
                ingestionCoordinatorService.triggerIngestion(repositories.get(0).getId());
        IngestionBatch batch2 =
                ingestionCoordinatorService.triggerIngestion(repositories.get(1).getId());
        IngestionBatch batch3 =
                ingestionCoordinatorService.triggerIngestion(repositories.get(2).getId());

        // Wait for first to start processing
        assertThat(cloneStarted.await(30, TimeUnit.SECONDS)).isTrue();

        // Verify initial queue positions
        int batch2InitialPosition =
                ingestionBatchRepository.countQueuedBatchesBefore(batch2.getStartedAt(), batch2.getId()) + 1;
        int batch3InitialPosition =
                ingestionBatchRepository.countQueuedBatchesBefore(batch3.getStartedAt(), batch3.getId()) + 1;

        assertThat(batch2InitialPosition).isEqualTo(1);
        assertThat(batch3InitialPosition).isEqualTo(2);

        blockExecution.countDown();

        // Wait for first to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch1 =
                            ingestionBatchRepository.findById(batch1.getId()).orElseThrow();
                    assertThat(updatedBatch1.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });

        // Batch 2 should now be processing (no longer QUEUED, so position query returns
        // 0)
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch2 =
                            ingestionBatchRepository.findById(batch2.getId()).orElseThrow();
                    assertThat(updatedBatch2.getStatus()).isIn(IngestionStatus.PROCESSING, IngestionStatus.SUCCESS);
                });

        // Batch 3 should still be QUEUED with position 1 (batch 2 is no longer queued)
        IngestionBatch updatedBatch3 =
                ingestionBatchRepository.findById(batch3.getId()).orElseThrow();
        if (updatedBatch3.getStatus() == IngestionStatus.QUEUED) {
            int batch3NewPosition =
                    ingestionBatchRepository.countQueuedBatchesBefore(batch3.getStartedAt(), batch3.getId()) + 1;
            assertThat(batch3NewPosition).isEqualTo(1);
        }

        // Wait for all remaining batches to finish to prevent background threads from
        // leaking row
        // locks into the next test class
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch finalBatch2 =
                            ingestionBatchRepository.findById(batch2.getId()).orElseThrow();
                    IngestionBatch finalBatch3 =
                            ingestionBatchRepository.findById(batch3.getId()).orElseThrow();
                    assertThat(finalBatch2.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                    assertThat(finalBatch3.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });
    }

    @Test
    void rapidSuccessiveIngestionsOnSameRepo_eachQueuesIndependently() throws Exception {
        CountDownLatch cloneStarted = new CountDownLatch(1);
        CountDownLatch blockExecution = blockProcessExecutorUntilReleased(cloneStarted);

        // Trigger 3 ingestions on the SAME repository
        UUID repoId = repositories.getFirst().getId();

        IngestionBatch batch1 = ingestionCoordinatorService.triggerIngestion(repoId);
        IngestionBatch batch2 = ingestionCoordinatorService.triggerIngestion(repoId);
        IngestionBatch batch3 = ingestionCoordinatorService.triggerIngestion(repoId);

        // All batches should be created with unique IDs
        assertThat(batch1.getId()).isNotEqualTo(batch2.getId());
        assertThat(batch2.getId()).isNotEqualTo(batch3.getId());

        // Wait for the first one to start processing to ensure states are settled
        assertThat(cloneStarted.await(30, TimeUnit.SECONDS)).isTrue();

        // All should be in QUEUED or PROCESSING state
        List<IngestionBatch> allBatches = ingestionBatchRepository.findByRepositoryId(repoId);
        assertThat(allBatches).hasSize(3);

        // At least one should be QUEUED (since concurrency is 1, only one can be
        // PROCESSING)
        long queuedCount = allBatches.stream()
                .filter(b -> b.getStatus() == IngestionStatus.QUEUED)
                .count();
        assertThat(queuedCount).isEqualTo(2);

        blockExecution.countDown();

        // Wait for all ingestions to finish to prevent background threads from leaking
        // row locks into the next test class
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    List<IngestionBatch> updatedBatches = ingestionBatchRepository.findByRepositoryId(repoId);
                    assertThat(updatedBatches).allMatch(b -> b.getStatus() == IngestionStatus.SUCCESS);
                });
    }

    private CountDownLatch blockProcessExecutorUntilReleased(CountDownLatch started) throws Exception {
        CountDownLatch blockExecution = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    started.countDown();
                    if (!blockExecution.await(15, TimeUnit.SECONDS)) {
                        throw new IOException("ProcessExecutor timed out waiting for release");
                    }
                    return invocation.callRealMethod();
                })
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());
        return blockExecution;
    }
}
