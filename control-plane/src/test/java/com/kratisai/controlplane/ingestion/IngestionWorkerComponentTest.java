package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.*;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class IngestionWorkerComponentTest {

    @Autowired
    private IngestionWorker ingestionWorker;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private IngestionBatchLogService ingestionBatchLogService;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private LiteLLMProvisioningService litellmProvisioningService;

    @TempDir
    Path tempDir;

    private Repository repository;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        // Reset the spy to ensure it delegates to the real method, clearing any
        // stubbing from other tests
        Mockito.reset(processExecutor);

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("test-team", "integration-repo");
        litellmProvisioningService.ensureModelsRegistered(
                ctx.team().getIngestionProvider(),
                ctx.team().getIngestionModel(),
                ctx.team().getEmbeddingProvider(),
                ctx.team().getEmbeddingModel());
        repository = ctx.repository();

        // Create minimal git repo in tempDir
        Path sourceRepoDir = tempDir.resolve("integration-repo-source");
        Files.createDirectories(sourceRepoDir);

        // Copy real sample files from codebase-memory/samples/java
        Path sampleDir = Path.of("src/test/resources/codebase-memory/samples/java");
        try (var stream = java.nio.file.Files.walk(sampleDir)) {
            stream.filter(java.nio.file.Files::isRegularFile).forEach(source -> {
                try {
                    Path target = sourceRepoDir.resolve(sampleDir.relativize(source));
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to copy sample file: " + source, e);
                }
            });
        }

        new ProcessBuilder("git", "init")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "add", ".")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder(
                        "git",
                        "-c",
                        "user.name=Test",
                        "-c",
                        "user.email=test@example.com",
                        "commit",
                        "-m",
                        "Initial commit")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "branch", "-M", "main")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();

        String repoUrl = "file://" + sourceRepoDir.toAbsolutePath();
        repository.setUrl(repoUrl);
        repositoryRepository.saveAndFlush(repository);
    }

    @Test
    void shouldCompleteIngestionAndSaveNodesOnSuccess() {
        // Create and save a pending batch
        IngestionBatch batch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(batch);

        // Run the ingestion worker (triggers async thread execution)
        ingestionWorker.runIngestion(batch.getId());

        // Wait and assert the batch completed successfully
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    IngestionBatch finishedBatch =
                            ingestionBatchRepository.findById(batch.getId()).orElseThrow();
                    assertThat(finishedBatch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                    assertThat(finishedBatch.getCommitHash()).isNotNull();
                });

        // Assert that the CtxNodes were correctly parsed and persisted by
        // codebase-memory
        List<CtxNode> nodes = ctxNodeRepository.findAll();
        assertThat(nodes).isNotEmpty();
        assertThat(nodes)
                .anyMatch(n -> n.getNodeType() == com.kratisai.controlplane.model.NodeType.FILE
                        && "UserController.java".equals(n.getPath()));
        assertThat(nodes)
                .anyMatch(n -> n.getNodeType() == com.kratisai.controlplane.model.NodeType.CLASS
                        && "UserController".equals(n.getSymbolName()));
    }

    @Test
    void shouldFailIngestionWhenParserThrowsException() throws Exception {
        Mockito.doAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (command != null && command.size() >= 3 && "index_repository".equals(command.get(2))) {
                        throw new IOException(
                                "Cannot run program 'codebase-memory': error=2, No such file or directory");
                    }
                    return invocation.callRealMethod();
                })
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        // Create and save a pending batch
        IngestionBatch batch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(batch);

        // Run the worker
        ingestionWorker.runIngestion(batch.getId());

        // Wait and assert the batch failed with details
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    IngestionBatch finishedBatch =
                            ingestionBatchRepository.findById(batch.getId()).orElseThrow();
                    assertThat(finishedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(finishedBatch.getErrorMessage())
                            .contains("codebase-memory parsing failed: Cannot run program 'codebase-memory'");
                    assertThat(finishedBatch.isActive()).isFalse();
                });

        // No nodes should have been saved
        assertThat(ctxNodeRepository.findAll()).isEmpty();
    }

    @Test
    void shouldFailIngestionAndReportNonZeroParserExitCode() throws Exception {
        String loaderError = "Error relocating /usr/local/bin/codebase-memory-mcp: __printf_chk: symbol not found";
        Mockito.doAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (command != null && command.size() >= 3 && "index_repository".equals(command.get(2))) {
                        return new ProcessExecutor.ProcessResult(
                                127, loaderError.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return invocation.callRealMethod();
                })
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        // Create and save a pending batch
        IngestionBatch batch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(batch);

        // Run the worker
        ingestionWorker.runIngestion(batch.getId());

        // Wait and assert the batch failed with the exit code and captured output
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    IngestionBatch finishedBatch =
                            ingestionBatchRepository.findById(batch.getId()).orElseThrow();
                    assertThat(finishedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(finishedBatch.getErrorMessage())
                            .contains("codebase-memory exited with code 127")
                            .contains("__printf_chk");
                    assertThat(finishedBatch.isActive()).isFalse();
                });

        // No nodes should have been saved
        assertThat(ctxNodeRepository.findAll()).isEmpty();
    }

    @Test
    void shouldHandleMissingBatchGracefully() {
        // Run with an invalid ID
        UUID fakeId = java.util.UUID.randomUUID();
        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(fakeId, null, ingestionBatchLogService);
        assertThatThrownBy(() -> ingestionWorker.performIngestion(fakeId, batchLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingestion batch not found: " + fakeId);
    }
}
