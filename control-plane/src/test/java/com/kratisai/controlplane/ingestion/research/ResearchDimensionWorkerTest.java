package com.kratisai.controlplane.ingestion.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.*;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionPipelineAbortException;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@SpringIntegrationTest
class ResearchDimensionWorkerTest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private ResearchDimensionWorker researchDimensionWorker;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private IngestionBatchLogService ingestionBatchLogService;

    @Value("${kratis.ingestion.clone-dir:${java.io.tmpdir}/kratis-ingest}")
    private String baseCloneDir;

    private Team team;
    private IngestionBatch batch;
    private CtxNode fileNode;
    private CtxDimension dimension;
    private Path tempCloneDir;

    @BeforeEach
    void setUp() throws IOException {
        fakeChatModel.reset();
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("Test Team", "test-repo");
        team = context.team();
        batch = context.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        fileNode = new CtxNode(batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/Test.java");
        ctxNodeRepository.save(fileNode);

        dimension = new CtxDimension(
                batch, team.getId(), DimensionCategory.ARCHETYPE, "TestDimension", null, List.of("*.java"));
        ctxDimensionRepository.save(dimension);

        // Create temp clone directory and file for ReadFileTool
        tempCloneDir = Path.of(baseCloneDir, batch.getId().toString());
        Files.createDirectories(tempCloneDir.resolve("src/main/java"));
        Files.writeString(tempCloneDir.resolve("src/main/java/Test.java"), "public class Test {}");
    }

    @AfterEach
    void tearDown() throws IOException {
        databaseCleaner.cleanAll();
        if (tempCloneDir != null && Files.exists(tempCloneDir)) {
            try (var stream = Files.walk(tempCloneDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // ignore
                    }
                });
            }
        }
    }

    private void setupDimensionHub(CtxNode node, CtxDimension dim, double rankScore) {
        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dim, rankScore);
        ctxNodeDimensionRepository.save(nodeDimension);
    }

    @Test
    void shouldGenerateSynopsisSuccessfully() {
        setupDimensionHub(fileNode, dimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder().response("""
                        {
                          "synopsis": "This is a test dimension synopsis."
                        }
                        """).build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);
        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);
        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);

        assertThat(result).isNotNull();
        assertThat(result.synopsis()).contains("test dimension synopsis");
    }

    @Test
    void shouldGenerateSynopsisForDomainCategory() {
        dimension.setCategory(DimensionCategory.DOMAIN);
        ctxDimensionRepository.save(dimension);
        setupDimensionHub(fileNode, dimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> {
                    String contents = p.getContents().toLowerCase();
                    return contents.contains("business domain")
                            && !contents.contains("codebase dimension")
                            && !contents.contains("dimension category");
                })
                .response("""
                        {
                          "synopsis": "This is a test domain synopsis."
                        }
                        """)
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);
        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);
        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);

        assertThat(result).isNotNull();
        assertThat(result.synopsis()).contains("test domain synopsis");
    }

    @Test
    void shouldGenerateSynopsisForArchetypeCategory() {
        dimension.setCategory(DimensionCategory.ARCHETYPE);
        ctxDimensionRepository.save(dimension);
        setupDimensionHub(fileNode, dimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> {
                    String contents = p.getContents().toLowerCase();
                    return contents.contains("architectural archetype")
                            && !contents.contains("codebase dimension")
                            && !contents.contains("dimension category");
                })
                .response("""
                        {
                          "synopsis": "This is a test archetype synopsis."
                        }
                        """)
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);
        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);
        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);

        assertThat(result).isNotNull();
        assertThat(result.synopsis()).contains("test archetype synopsis");
    }

    @Test
    void shouldGenerateSynopsisForCrossCuttingCategory() {
        dimension.setCategory(DimensionCategory.CROSS_CUTTING);
        ctxDimensionRepository.save(dimension);
        setupDimensionHub(fileNode, dimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .condition(p -> {
                    String contents = p.getContents().toLowerCase();
                    return contents.contains("cross-cutting utility")
                            && !contents.contains("codebase dimension")
                            && !contents.contains("dimension category");
                })
                .response("""
                        {
                          "synopsis": "This is a test cross-cutting synopsis."
                        }
                        """)
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);
        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);
        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);

        assertThat(result).isNotNull();
        assertThat(result.synopsis()).contains("test cross-cutting synopsis");
    }

    @Test
    void shouldRetryOnSoftFailure() {
        setupDimensionHub(fileNode, dimension, 0.9);

        AtomicInteger callCount = new AtomicInteger(0);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    int count = callCount.incrementAndGet();
                    if (count <= 2) {
                        throw new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                    {
                                      "synopsis": "Retried successfully."
                                    }
                                    """))));
                })
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);
        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);
        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);

        assertThat(result).isNotNull();
        assertThat(result.synopsis()).contains("Retried successfully");
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void shouldAbortOnHardFailure() {
        setupDimensionHub(fileNode, dimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED);
                })
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);

        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);

        assertThatThrownBy(() -> researchDimensionWorker.research(task, batchLogger, batch))
                .isInstanceOf(IngestionPipelineAbortException.class)
                .hasMessageContaining("Authentication/Authorization failed");
    }

    @Test
    void shouldAbortOnUnexpectedHttpError() {
        setupDimensionHub(fileNode, dimension, 0.9);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
                })
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);

        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);

        assertThatThrownBy(() -> researchDimensionWorker.research(task, batchLogger, batch))
                .isInstanceOf(IngestionPipelineAbortException.class)
                .hasMessageContaining("Unexpected HTTP client error");
    }

    @Test
    void shouldRetryOnResourceAccessException() {
        setupDimensionHub(fileNode, dimension, 0.9);

        AtomicInteger callCount = new AtomicInteger(0);

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(prompt -> {
                    int count = callCount.incrementAndGet();
                    if (count <= 1) {
                        throw new ResourceAccessException("Connection refused");
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                                    {
                                      "synopsis": "Retried after resource access exception."
                                    }
                                    """))));
                })
                .build());

        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batch.getId(), team.getId(), ingestionBatchLogService);
        ResearchDimensionTask task = new ResearchDimensionTask(
                batch.getId(), team.getIngestionProvider(), team.getIngestionModel(), dimension);
        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);

        assertThat(result).isNotNull();
        assertThat(result.synopsis()).contains("Retried after resource access exception");
        assertThat(callCount.get()).isEqualTo(2);
    }
}
