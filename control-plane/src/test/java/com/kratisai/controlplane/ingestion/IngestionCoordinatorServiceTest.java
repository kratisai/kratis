package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.testsupport.llm.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.ingestion.parse.CodebaseMemoryParserService;
import com.kratisai.controlplane.ingestion.surfaces.SurfaceExtractionService;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.IngestionTestStubs;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
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
    private IngestionBatchLogService ingestionBatchLogService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private CodebaseMemoryParserService codebaseMemoryParserService;

    @Autowired
    private SurfaceExtractionService surfaceExtractionService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private LiteLLMProperties liteLLMProperties;

    private Repository repository;
    private Duration previousFinalizeDelay;

    @BeforeEach
    void setUp() {
        previousFinalizeDelay = liteLLMProperties.getUsageFinalizeDelay();
        liteLLMProperties.setUsageFinalizeDelay(Duration.ZERO);
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        Mockito.reset(processExecutor);
        IngestionTestStubs.stubFastParserAndSurfaces(
                codebaseMemoryParserService, surfaceExtractionService, ctxNodeRepository, ctxEdgeRepository);

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("test-team", "kratis-core");
        repository = ctx.repository();
    }

    @AfterEach
    void tearDown() {
        liteLLMProperties.setUsageFinalizeDelay(previousFinalizeDelay);
    }

    @Test
    void shouldTriggerAndCompleteIngestionAsynchronously() {

        // Trigger ingestion - should return immediately with QUEUED state
        IngestionBatch batch = ingestionCoordinatorService.triggerIngestion(repository.getId());

        assertThat(batch).isNotNull();
        assertThat(batch.getId()).isNotNull();
        assertThat(batch.getStatus()).isEqualTo(IngestionStatus.QUEUED);

        // Verify async thread completed the processing via Awaitility
        IngestionBatch updatedBatch = awaitStatus(batch.getId(), IngestionStatus.SUCCESS);
        assertThat(updatedBatch.getCommitHash()).isNotNull();
        assertThat(updatedBatch.isActive()).isTrue();
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

    private IngestionBatch awaitStatus(UUID batchId, IngestionStatus expected) {
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertThat(ingestionBatchRepository
                                    .findById(batchId)
                                    .orElseThrow()
                                    .getStatus())
                            .isEqualTo(expected));
        } catch (ConditionTimeoutException exception) {
            String logs = ingestionBatchLogService.getLogsForBatch(batchId).stream()
                    .map(log -> log.getLevel() + " " + log.getStep() + " " + log.getMessage())
                    .toList()
                    .toString();
            throw new AssertionError("Batch " + batchId + " did not reach " + expected + ": " + logs, exception);
        }
        return ingestionBatchRepository.findById(batchId).orElseThrow();
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
        IngestionBatch updatedNew = awaitStatus(newBatch.getId(), IngestionStatus.SUCCESS);
        assertThat(updatedNew.isActive()).isTrue();

        // Verify the old batch is now deactivated
        IngestionBatch updatedOld =
                ingestionBatchRepository.findById(oldBatch.getId()).orElseThrow();
        assertThat(updatedOld.isActive()).isFalse();
    }
}
