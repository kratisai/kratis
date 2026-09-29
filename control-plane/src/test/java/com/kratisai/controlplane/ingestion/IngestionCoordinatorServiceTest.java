package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.*;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class IngestionCoordinatorServiceTest {

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

    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        Mockito.reset(processExecutor);

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("test-team", "kratis-core");
        repository = ctx.repository();
    }

    @Test
    void shouldTriggerAndCompleteIngestionAsynchronously() {

        // Trigger ingestion - should return immediately with QUEUED state
        IngestionBatch batch = ingestionCoordinatorService.triggerIngestion(repository.getId());

        assertThat(batch).isNotNull();
        assertThat(batch.getId()).isNotNull();
        assertThat(batch.getStatus()).isEqualTo(IngestionStatus.QUEUED);

        // Verify async thread completed the processing via Awaitility
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch =
                            ingestionBatchRepository.findById(batch.getId()).orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                    assertThat(updatedBatch.getCommitHash()).isNotNull();
                    assertThat(updatedBatch.isActive()).isTrue();
                });
    }

    @Test
    void shouldHandleFailedIngestionGracefully() {
        repository.setUrl("file:///non-existent-directory-to-force-fail");
        repositoryRepository.saveAndFlush(repository);

        // Trigger ingestion
        IngestionBatch batch = ingestionCoordinatorService.triggerIngestion(repository.getId());

        assertThat(batch).isNotNull();

        // Verify batch recorded failure state
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            IngestionBatch updatedBatch =
                    ingestionBatchRepository.findById(batch.getId()).orElseThrow();
            assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
            assertThat(updatedBatch.getErrorMessage()).isNotNull();
            assertThat(updatedBatch.isActive()).isFalse();
        });
    }

    @Test
    void shouldMaintainOnlyOneActiveBatchPerRepository() {
        // Create an already active batch
        IngestionBatch oldBatch = new IngestionBatch(repository);
        oldBatch.setActive(true);
        oldBatch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(oldBatch);

        // Trigger new ingestion
        IngestionBatch newBatch = ingestionCoordinatorService.triggerIngestion(repository.getId());

        // Verify batch was created with QUEUED status
        assertThat(newBatch.getStatus()).isEqualTo(IngestionStatus.QUEUED);

        // Wait for async processing to complete (increased timeout for CI environments)
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    IngestionBatch updatedNew =
                            ingestionBatchRepository.findById(newBatch.getId()).orElseThrow();
                    assertThat(updatedNew.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                    assertThat(updatedNew.isActive()).isTrue();
                });

        // Verify the old batch is now deactivated
        IngestionBatch updatedOld =
                ingestionBatchRepository.findById(oldBatch.getId()).orElseThrow();
        assertThat(updatedOld.isActive()).isFalse();
    }
}
