package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class IngestionPipelineE2ETest {

    @Autowired
    private IngestionCoordinatorService ingestionCoordinatorService;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Autowired
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Autowired
    private CtxEmbeddingRepository ctxEmbeddingRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private ProcessExecutor processExecutor;

    @TempDir
    Path tempDir;

    private Repository repository;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        // Reset the spy to ensure it delegates to the real method, clearing any stubbing from other tests
        Mockito.reset(processExecutor);

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("E2E Team", "e2e-repo");
        repository = ctx.repository();

        // Create minimal git repo in tempDir
        Path sourceRepoDir = tempDir.resolve("e2e-repo-source");
        Files.createDirectories(sourceRepoDir);

        // Copy real sample files from codebase-memory/samples/java
        Path sampleDir = Path.of("src/test/resources/codebase-memory/samples/java");
        try (var stream = Files.walk(sampleDir)) {
            stream.filter(Files::isRegularFile).forEach(source -> {
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
    void shouldRunEntireIngestionPipelineEndToEnd() {
        // 1. ProcessExecutor is a spy, allowing the real binary to parse the temp git repo and generate the SQLite DB.

        // 2. Configure FakeChatModel responses based on prompt contents
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        // 3. Trigger ingestion via Coordinator Service (simulating API call)
        IngestionBatch batch = ingestionCoordinatorService.triggerIngestion(repository.getId());

        // 4. Assertions via Awaitility
        // Wait for batch to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    IngestionBatch finishedBatch =
                            ingestionBatchRepository.findById(batch.getId()).orElseThrow();
                    assertThat(finishedBatch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });

        // Assert CtxNode Records (codebase-memory generates FILE, CLASS, METHOD, etc.)
        List<CtxNode> nodes = ctxNodeRepository.findAll();
        assertThat(nodes).isNotEmpty();

        // Verify specific node types were created by codebase-memory
        assertThat(nodes).anyMatch(n -> n.getNodeType() == NodeType.FILE && "UserController.java".equals(n.getPath()));
        assertThat(nodes)
                .anyMatch(n -> n.getNodeType() == NodeType.CLASS && "UserController".equals(n.getSymbolName()));
        assertThat(nodes).anyMatch(n -> n.getNodeType() == NodeType.CLASS && "UserService".equals(n.getSymbolName()));
        assertThat(nodes)
                .anyMatch(
                        n -> n.getNodeType() == NodeType.INTERFACE && "UserServiceInterface".equals(n.getSymbolName()));

        // Verify nodes are tagged with dimensions
        CtxNode controllerNode = nodes.stream()
                .filter(n -> n.getPath().endsWith("UserController.java") && n.getNodeType() == NodeType.FILE)
                .findFirst()
                .orElseThrow();

        List<CtxNodeDimension> controllerNodeDimensions =
                ctxNodeDimensionRepository.findByNodeIdWithDimension(controllerNode.getId());
        assertThat(controllerNodeDimensions).isNotEmpty();
        assertThat(controllerNodeDimensions.stream().map(nd -> nd.getDimension().getName()))
                .contains("Controller");

        CtxNode serviceNode = nodes.stream()
                .filter(n -> n.getPath().endsWith("UserService.java") && n.getNodeType() == NodeType.FILE)
                .findFirst()
                .orElseThrow();

        List<CtxNodeDimension> serviceNodeDimensions =
                ctxNodeDimensionRepository.findByNodeIdWithDimension(serviceNode.getId());
        assertThat(serviceNodeDimensions).isNotEmpty();
        assertThat(serviceNodeDimensions.stream().map(nd -> nd.getDimension().getName()))
                .contains("Service");

        // Assert Dimensions & Node Dimensions
        List<CtxDimension> dimensions = ctxDimensionRepository.findAll();
        assertThat(dimensions).isNotEmpty();
        assertThat(dimensions).extracting(CtxDimension::getName).contains("User Management");
        assertThat(dimensions)
                .allMatch(d -> d.getSynopsis() != null && !d.getSynopsis().isBlank());

        List<CtxNodeDimension> nodeDimensions = ctxNodeDimensionRepository.findAll();
        assertThat(nodeDimensions).isNotEmpty();

        // Assert Wiki Page
        List<CtxWikiPage> pages = ctxWikiPageRepository.findByBatchId(batch.getId());
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().getPageSlug()).isEqualTo("user-management-overview");
        assertThat(pages.getFirst().getTitle()).isEqualTo("User Management Overview");
        assertThat(pages.getFirst().getContent()).contains("# User Management");

        // Assert Embeddings
        List<CtxEmbedding> embeddings = ctxEmbeddingRepository.findAll();
        assertThat(embeddings).isNotEmpty();
        assertThat(embeddings.getFirst().getEmbedding()).isNotNull();

        // Assert Architecture Patterns
        List<CtxArchitecturePattern> archPatterns = ctxArchitecturePatternRepository.findByBatchId(batch.getId());
        assertThat(archPatterns).isNotEmpty();
        assertThat(archPatterns.getFirst().getDescription()).contains("Monolithic MVC Service Architecture");
    }
}
