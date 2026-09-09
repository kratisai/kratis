package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
public class IngestionWorkerIntegrationTest {

    @Autowired
    private IngestionWorker ingestionWorker;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private LiteLLMProvisioningService litellmProvisioningService;

    private Path tempRepoDir;

    @BeforeEach
    void setUp() throws Exception {
        fakeChatModel.reset();
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        // Reset the spy to ensure it delegates to the real method, clearing any stubbing from other tests
        Mockito.reset(processExecutor);

        // Create a temporary local git repository to allow GitCloneService to succeed
        tempRepoDir = Files.createTempDirectory("test-local-repo");
        copySampleJavaSources(tempRepoDir);
        runGitCommand(tempRepoDir, "init");
        runGitCommand(tempRepoDir, "config", "user.email", "test@test.com");
        runGitCommand(tempRepoDir, "config", "user.name", "Test User");
        runGitCommand(tempRepoDir, "add", ".");
        runGitCommand(tempRepoDir, "commit", "-m", "Initial commit");
        runGitCommand(tempRepoDir, "branch", "-M", "main");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempRepoDir != null) {
            deleteDirectory(tempRepoDir);
        }
    }

    private void runGitCommand(Path dir, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git");
        for (String arg : args) {
            pb.command().add(arg);
        }
        pb.directory(dir.toFile());
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Git command failed: " + String.join(" ", args));
        }
    }

    // Us a file-repo instead of a URL to avoid DNS timeout variability resolving the url
    private String nonexistentRepoUrl() {
        Path path = Path.of(
                System.getProperty("java.io.tmpdir"), "kratis-test-nonexistent-" + UUID.randomUUID(), "repo.git");
        return path.toUri().toString();
    }

    private void registerTeamModels(TestDataFactory.TestContext ctx) {
        litellmProvisioningService.ensureModelsRegistered(
                ctx.team().getIngestionProvider(),
                ctx.team().getIngestionModel(),
                ctx.team().getEmbeddingProvider(),
                ctx.team().getEmbeddingModel());
    }

    private void deleteDirectory(Path path) throws Exception {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception e) {
                        // ignore
                    }
                });
            }
        }
    }

    private void copySampleJavaSources(Path repoDir) throws Exception {
        Path sampleDir = Path.of("src/test/resources/codebase-memory/samples/java");
        try (var stream = Files.walk(sampleDir)) {
            stream.filter(Files::isRegularFile).forEach(source -> {
                try {
                    Path target = repoDir.resolve(sampleDir.relativize(source));
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to copy sample file: " + source, e);
                }
            });
        }
    }

    @Test
    void testHappyPathIngestion() {
        // 1. Setup: Create a team (with ingestion provider), repository, and ingestion batch
        TestDataFactory.TestContext ctx = testDataFactory.createContextWithCustomRepo(
                "Test Team", "test-repo", tempRepoDir.toUri().toString());
        registerTeamModels(ctx);

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.QUEUED);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // 2. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 3. Assertion: Verify batch transitions to SUCCESS
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
                });
    }

    @Test
    void testEarlyPhaseFailureGitClone() {
        // 1. Setup: Create a team, repository with invalid URL, and ingestion batch
        TestDataFactory.TestContext ctx =
                testDataFactory.createContextWithCustomRepo("Test Team", "invalid-repo", nonexistentRepoUrl());
        registerTeamModels(ctx);

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.QUEUED);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // 2. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 3. Assertion: Verify batch transitions to FAILED.
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(updatedBatch.getErrorMessage()).contains("Failed to clone repository");
                });
    }

    @Test
    void testPartialDataRetentionOnAstParsingFailure() throws Exception {
        // 1. Setup: Create a team, repository, and ingestion batch
        TestDataFactory.TestContext ctx = testDataFactory.createContextWithCustomRepo(
                "Test Team", "test-repo", tempRepoDir.toUri().toString());
        registerTeamModels(ctx);
        Team team = ctx.team();
        Repository repo = ctx.repository();

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.PROCESSING);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // Pre-populate some CtxNode and CtxEdge data to simulate successful CLONE phase
        CtxNode node1 = new CtxNode(savedBatch, team.getId(), repo.getName(), NodeType.FILE, "src/Main.java");
        ctxNodeRepository.save(node1);

        CtxNode node2 = new CtxNode(savedBatch, team.getId(), repo.getName(), NodeType.FILE, "src/Utils.java");
        ctxNodeRepository.save(node2);

        CtxEdge edge = new CtxEdge(savedBatch, team.getId(), node1, node2, RelationType.IMPORTS);
        ctxEdgeRepository.save(edge);

        // 2. Configure boundary mock to fail during AST parsing (PREPARE_AST phase)
        Mockito.reset(processExecutor);
        Mockito.doAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    if (command != null && command.size() >= 3 && "index_repository".equals(command.get(2))) {
                        throw new IOException("Simulated codebase-memory CLI failure");
                    }
                    return invocation.callRealMethod();
                })
                .when(processExecutor)
                .execute(Mockito.anyList(), Mockito.any(File.class), Mockito.any());

        // 3. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 4. Assertion: Verify batch transitions to FAILED
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(updatedBatch.getErrorMessage()).contains("Simulated codebase-memory CLI failure");
                });

        // 5. Assertion: Verify partial data (nodes and edges) is RETAINED
        List<CtxNode> retainedNodes = ctxNodeRepository.findByBatchId(savedBatch.getId());
        assertThat(retainedNodes).hasSize(2);
        assertThat(retainedNodes)
                .extracting(CtxNode::getPath)
                .containsExactlyInAnyOrder("src/Main.java", "src/Utils.java");

        List<CtxEdge> retainedEdges = ctxEdgeRepository.findByBatchIdWithNodes(savedBatch.getId());
        assertThat(retainedEdges).hasSize(1);
        assertThat(retainedEdges.getFirst().getRelationType()).isEqualTo(RelationType.IMPORTS);
    }

    @Test
    void testPartialDataRetentionOnDimensionResearchFailure() {
        // 1. Setup: Create a team, repository, and ingestion batch
        TestDataFactory.TestContext ctx = testDataFactory.createContextWithCustomRepo(
                "Test Team", "test-repo", tempRepoDir.toUri().toString());
        registerTeamModels(ctx);
        Team team = ctx.team();
        Repository repo = ctx.repository();

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.PROCESSING);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // Pre-populate some CtxDimension and CtxNodeDimension data to simulate successful PREPARE_DIMENSIONS phase
        CtxDimension dimension = new CtxDimension(
                savedBatch,
                team.getId(),
                DimensionCategory.ARCHETYPE,
                "Test Dimension",
                "Test synopsis",
                List.of("src/**/*.java"));
        dimension = ctxDimensionRepository.save(dimension);

        CtxNode node = new CtxNode(savedBatch, team.getId(), repo.getName(), NodeType.FILE, "src/Main.java");
        node = ctxNodeRepository.save(node);

        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dimension, 0.95);
        ctxNodeDimensionRepository.save(nodeDimension);

        // 2. Configure FakeChatModel to fail during dimension research (RESEARCH_DIMENSIONS phase)
        // Use addMatcherFirst to ensure it takes precedence over the default pipeline matchers
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("generate a rich synopsis for a specific")
                .response(p -> {
                    throw new RuntimeException("Simulated dimension research failure");
                })
                .build());

        // 3. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 4. Assertion: Verify batch transitions to FAILED
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(updatedBatch.getErrorMessage()).contains("Failed to research dimension");
                });

        // 5. Assertion: Verify partial data (dimensions and node dimensions) is RETAINED
        List<CtxDimension> retainedDimensions = ctxDimensionRepository.findByBatchId(savedBatch.getId());
        assertThat(retainedDimensions).hasSizeGreaterThan(0);
        assertThat(retainedDimensions).anyMatch(d -> "Test Dimension".equals(d.getName()));

        List<CtxNodeDimension> retainedNodeDimensions = ctxNodeDimensionRepository.findByDimensionId(dimension.getId());
        assertThat(retainedNodeDimensions).hasSize(1);
        assertThat(retainedNodeDimensions.getFirst().getRankScore()).isNotNull();
    }

    @Test
    void testVirtualKeyLifecycleOnSuccess() {
        // 1. Setup: Create a team with ingestion provider, repository, and ingestion batch
        TestDataFactory.TestContext ctx = testDataFactory.createContextWithCustomRepo(
                "Test Team", "test-repo", tempRepoDir.toUri().toString());
        registerTeamModels(ctx);

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.QUEUED);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // 2. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 3. Assertion: Verify batch transitions to SUCCESS
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.SUCCESS);

                    // Verify virtual key was generated and set
                    assertThat(updatedBatch.getUsage()).isNotNull();
                    assertThat(updatedBatch.getUsage().getVirtualKey()).isNotNull();
                    assertThat(updatedBatch.getUsage().getVirtualKey()).startsWith("sk-");

                    // Verify usage was refreshed from tracked model responses (LiteLLM
                    // /key/info does not report token counts)
                    assertThat(updatedBatch.getUsage().getUsageLastUpdatedAt()).isNotNull();
                    assertThat(updatedBatch.getUsage().getTotalTokens()).isGreaterThan(0);
                    assertThat(updatedBatch.getUsage().getPromptTokens()).isGreaterThan(0);
                    assertThat(updatedBatch.getUsage().getCompletionTokens()).isGreaterThan(0);

                    // Verify tool calls from the wiki generation ReAct loop were counted
                    assertThat(updatedBatch.getTotalToolCalls()).isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    void testVirtualKeyRevokedOnFailure() {
        // 1. Setup: Create a team with ingestion provider, repository with invalid URL, and ingestion batch
        TestDataFactory.TestContext ctx =
                testDataFactory.createContextWithCustomRepo("Test Team", "invalid-repo", nonexistentRepoUrl());
        registerTeamModels(ctx);

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.QUEUED);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // 2. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 3. Assertion: Verify batch transitions to FAILED
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(updatedBatch.getErrorMessage()).contains("Failed to clone repository");

                    // Verify virtual key was generated before failure
                    assertThat(updatedBatch.getUsage()).isNotNull();
                    assertThat(updatedBatch.getUsage().getVirtualKey()).isNotNull();

                    // Verify usage refresh was attempted even on failure
                    assertThat(updatedBatch.getUsage().getUsageLastUpdatedAt()).isNotNull();
                });
    }

    @Test
    void testIngestionFailsWhenIngestionModelMissing() {
        // 1. Setup: Create a team without ingestion model configured
        TestDataFactory.TestContext ctx = testDataFactory.createContextWithCustomRepo(
                "Test Team", "test-repo", tempRepoDir.toUri().toString());
        registerTeamModels(ctx);
        Team team = ctx.team();

        // Remove ingestion model to trigger the exception
        team.setIngestionModel(null);
        teamRepository.save(team);

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.QUEUED);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // 2. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 3. Assertion: Verify batch transitions to FAILED with appropriate error message
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(updatedBatch.getErrorMessage()).contains("Models are required for ingestion");
                });
    }

    @Test
    void testIngestionFailsWhenEmbeddingModelMissing() {
        // 1. Setup: Create a team without embedding model configured
        TestDataFactory.TestContext ctx = testDataFactory.createContextWithCustomRepo(
                "Test Team", "test-repo", tempRepoDir.toUri().toString());
        registerTeamModels(ctx);
        Team team = ctx.team();

        // Remove embedding model to trigger the exception
        team.setEmbeddingModel(null);
        teamRepository.save(team);

        IngestionBatch batch = ctx.batch();
        batch.setStatus(IngestionStatus.QUEUED);
        IngestionBatch savedBatch = ingestionBatchRepository.save(batch);

        // 2. Action: Run ingestion
        ingestionWorker.runIngestion(savedBatch.getId());

        // 3. Assertion: Verify batch transitions to FAILED with appropriate error message
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatch = ingestionBatchRepository
                            .findById(savedBatch.getId())
                            .orElseThrow();
                    assertThat(updatedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(updatedBatch.getErrorMessage()).contains("embedding model is required for ingestion");
                });
    }
}
