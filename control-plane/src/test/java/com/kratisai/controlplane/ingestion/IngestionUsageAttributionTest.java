package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModelConfig;
import com.kratisai.controlplane.HttpRequestMatcher;
import com.kratisai.controlplane.LlmMockScenarios;
import com.kratisai.controlplane.LlmResponseBuilders;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.UseRealEmbeddingClient;
import com.kratisai.controlplane.UseRealLlmClient;
import com.kratisai.controlplane.WireMockLlmServer;
import com.kratisai.controlplane.config.TestIngestionExecutorConfig;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.IngestionModelUsageRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringIntegrationTest
@Import(TestIngestionExecutorConfig.class)
@TestPropertySource(
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            // Embedding spend rows land seconds after the call via LiteLLM's async flush;
            // allow extra settle time before the single finalize fetch.
            "kratis.litellm.usage-finalize-delay=15s"
        })
class IngestionUsageAttributionTest {

    @Autowired
    private IngestionWorker ingestionWorker;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private IngestionModelUsageRepository ingestionModelUsageRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private com.kratisai.controlplane.config.LiteLLMProperties liteLLMProperties;

    @Autowired
    private LiteLLMProvisioningService liteLLMProvisioningService;

    @TempDir
    Path tempDir;

    private WireMockLlmServer wireMock;

    private com.kratisai.controlplane.model.Repository repository;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();

        wireMock = new WireMockLlmServer();
        LlmMockScenarios.openAiCompat(wireMock);
        // Every chat-completions shape the pipeline emits: single-shot JSON answers and
        // the ReAct tool-call turns in wiki/pattern research.
        wireMock.addMatchers(List.of(
                HttpRequestMatcher.builder()
                        .contains("write_wiki_page")
                        .sseResponse(LlmResponseBuilders.openAiSseToolCall(
                                "write_wiki_page",
                                "{\"pageSlug\":\"overview\",\"title\":\"Overview\",\"content\":\"# Overview\"}"))
                        // The tool-call turn stays in the conversation history, so without a
                        // match cap every follow-up turn would loop back into another tool call.
                        .maxMatches(1)
                        .build(),
                HttpRequestMatcher.builder()
                        .contains("deduce the primary architectural dimensions")
                        .jsonResponse(LlmResponseBuilders.openAiText(
                                "{\"domains\": [{\"name\": \"User Management\", \"globPatterns\": [\"*\"]}],"
                                        + " \"archetypes\": [{\"name\": \"Controller\", \"globPatterns\": [\"*\"]}],"
                                        + " \"crossCutting\": [{\"name\": \"Utils\", \"globPatterns\": [\"*\"]}],"
                                        + " \"additionalContextRequest\": null}"))
                        .build(),
                HttpRequestMatcher.builder()
                        .contains("generate a rich synopsis for a specific")
                        .sseResponse(
                                LlmResponseBuilders.openAiSseText("{\"synopsis\": \"A core business capability.\"}"))
                        .build(),
                HttpRequestMatcher.builder()
                        .contains("deduce the system-wide architecture patterns")
                        .sseResponse(LlmResponseBuilders.openAiSseText(
                                "[{\"name\": \"Monolithic MVC\", \"description\": \"Monolith.\","
                                        + " \"exemplarPaths\": [\"UserController.java\"]}]"))
                        .build(),
                HttpRequestMatcher.builder()
                        .contains("synthesize the human-readable")
                        .sseResponse(LlmResponseBuilders.openAiSseText("Wiki generation complete."))
                        .build(),
                HttpRequestMatcher.builder()
                        .contains("\"input\"")
                        .jsonResponse(LlmResponseBuilders.openAiEmbedding(new float[] {0.1f, 0.2f, 0.3f}))
                        .build()));

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("test-team", "integration-repo");
        TestDataFactory.TestContext routed = testDataFactory.createContextWithWireMockUpstream(
                ctx.team(), wireMock.getBaseUrl(liteLLMProperties) + "/openai");
        repository = routed.repository();

        Path sourceRepoDir = tempDir.resolve("integration-repo-source");
        Files.createDirectories(sourceRepoDir);
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

        repository.setUrl("file://" + sourceRepoDir.toAbsolutePath());
        repositoryRepository.saveAndFlush(repository);
    }

    @AfterEach
    void tearDown() {
        FakeChatModelConfig.clear();
        wireMock.stop();
    }

    @Test
    @UseRealLlmClient
    @UseRealEmbeddingClient
    void attributesChatAndEmbeddingSpendToSeparateRows() {
        IngestionBatch batch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(batch);

        ingestionWorker.runIngestion(batch.getId());
        // Finalize runs after the SUCCESS marking (plus LiteLLM's async spend-log flush
        // delay), so await the finalize write itself: both rows carrying real tokens.
        Awaitility.await()
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(1000))
                .untilAsserted(() -> {
                    List<IngestionModelUsage> usages = ingestionModelUsageRepository.findByBatchId(batch.getId());
                    assertThat(usages).hasSize(2);
                    assertThat(usages)
                            .filteredOn(u -> u.getModelKind() == ModelKind.CHAT)
                            .singleElement()
                            .satisfies(chat -> assertThat(chat.getTotalTokens()).isGreaterThan(0));
                    assertThat(usages)
                            .filteredOn(u -> u.getModelKind() == ModelKind.EMBEDDING)
                            .singleElement()
                            .satisfies(embedding ->
                                    assertThat(embedding.getTotalTokens()).isGreaterThan(0));
                });

        List<IngestionModelUsage> usages = ingestionModelUsageRepository.findByBatchId(batch.getId());
        assertThat(usages).hasSize(2);

        IngestionModelUsage chat = usages.stream()
                .filter(u -> u.getModelKind() == ModelKind.CHAT)
                .findFirst()
                .orElseThrow();
        IngestionModelUsage embedding = usages.stream()
                .filter(u -> u.getModelKind() == ModelKind.EMBEDDING)
                .findFirst()
                .orElseThrow();

        // Both rows carry the LiteLLM deployment alias behind this batch's single virtual key.
        var team = testDataFactory.getTeamWithProviders(repository.getTeam().getId());
        String expectedChatAlias =
                liteLLMProvisioningService.buildLiteLLMModelName(team.getIngestionProvider(), team.getIngestionModel());
        String expectedEmbeddingAlias =
                liteLLMProvisioningService.buildLiteLLMModelName(team.getEmbeddingProvider(), team.getEmbeddingModel());
        assertThat(chat.getLitellmAlias()).isEqualTo(expectedChatAlias);
        assertThat(embedding.getLitellmAlias()).isEqualTo(expectedEmbeddingAlias);

        // Chat traffic went through LiteLLM: the spend-log grouping attributed real tokens.
        assertThat(chat.getTotalTokens()).isGreaterThan(0);
        assertThat(chat.getPromptTokens()).isGreaterThan(0);

        // The embedding route produced spend-log rows of its own.
        assertThat(embedding.getTotalTokens()).isGreaterThan(0);

        assertThat(wireMock.countPostRequests("/openai/v1/chat/completions")).isGreaterThan(0);
        assertThat(wireMock.countPostRequests("/openai/v1/embeddings")).isGreaterThan(0);
    }
}
