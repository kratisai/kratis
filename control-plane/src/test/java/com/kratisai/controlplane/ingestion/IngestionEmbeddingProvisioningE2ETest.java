package com.kratisai.controlplane.ingestion;

import static com.kratisai.controlplane.FakeChatModelMatchers.ingestionPipelineMatchers;
import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.HttpRequestMatcher;
import com.kratisai.controlplane.LlmResponseBuilders;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.UseRealEmbeddingClient;
import com.kratisai.controlplane.WireMockLlmServer;
import com.kratisai.controlplane.api.restdto.UpdateTeamRequest;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.config.TestIngestionExecutorConfig;
import com.kratisai.controlplane.model.CtxEmbedding;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderModel;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ProcessExecutor;
import com.kratisai.controlplane.service.TeamService;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Full, real ingestion-pipeline interactions routing embeddings calls through LiteLLM.
 * <p/>
 * Captures errors related to the embedding model not being configured in LiteLLM and
 * integration issues with the embeddings requests sent to LiteLLM
 */
@UseRealEmbeddingClient
@SpringIntegrationTest
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(TestIngestionExecutorConfig.class)
class IngestionEmbeddingProvisioningE2ETest {

    @Autowired
    private IngestionCoordinatorService ingestionCoordinatorService;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private CtxEmbeddingRepository ctxEmbeddingRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private ProcessExecutor processExecutor;

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private LiteLLMProvisioningService liteLLMProvisioningService;

    @Autowired
    private LiteLLMProperties liteLLMProperties;

    @TempDir
    Path tempDir;

    private WireMockLlmServer wireMockLlmServer;
    private TestDataFactory.TestContext testContext;
    private String repoUrl;

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();
        Mockito.reset(processExecutor);

        wireMockLlmServer = new WireMockLlmServer();
        testContext = testDataFactory.createUserAndTeam(false);

        Path sourceRepoDir = tempDir.resolve("e2e-repo-source");
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
        this.repoUrl = "file://" + sourceRepoDir.toAbsolutePath();
    }

    private Repository createRepositoryForTeam(Team team) {
        Repository repo = new Repository("e2e-repo", repoUrl, "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        return repositoryRepository.saveAndFlush(repo);
    }

    @AfterEach
    void tearDown() {
        if (wireMockLlmServer != null) {
            wireMockLlmServer.stop();
        }
    }

    @Test
    void shouldCompleteIngestionWithRealEmbeddingsThroughLiteLLM_afterTeamServiceAutoProvisionsEmbeddingModel() {
        Team team = testContext.team();

        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        ModelProvider provider =
                new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-fake-key", dockerAccessibleBaseUrl);
        // Only a CHAT model is persisted on the provider up front -- the embedding model is
        // deliberately absent, simulating a value the admin picked from the settings UI's live
        // model discovery dropdown rather than the provider's persisted `models` list.
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        provider.setTeam(team);
        provider = modelProviderRepository.saveAndFlush(provider);
        liteLLMProvisioningService.provisionModel(provider);

        // This is the exact production write path: TeamService.updateTeam() must auto-provision
        // the previously-unregistered embedding model to LiteLLM as a side effect.
        UpdateTeamRequest updateRequest = new UpdateTeamRequest(
                null, null, null, provider.getId(), "gpt-4o", provider.getId(), "text-embedding-3-small");
        teamService.updateTeam(testContext.user().getId(), team.getId(), updateRequest);

        team = teamRepository.findById(team.getId()).orElseThrow();
        Repository repository = createRepositoryForTeam(team);

        runIngestionAndAssertRealEmbeddingSucceeded(repository);
    }

    /**
     * Reproduces the exact bug reported from a real running instance: a team whose embedding
     * provider/model were set directly on the {@code Team} entity -- simulating data that predates
     * this self-healing fix, or any future code path that sets these fields without going through
     * {@code TeamService.updateTeam()} -- and whose model was <em>never</em> provisioned to
     * LiteLLM at all. {@code TeamService.updateTeam()}'s provisioning-on-write is never invoked
     * here, so the only thing that can make this ingestion succeed is
     * {@code IngestionWorker}'s defensive {@code ensureModelRegistered} call immediately before
     * generating the batch's virtual key.
     */
    @Test
    void shouldCompleteIngestionWithRealEmbeddings_forTeamConfiguredWithoutEverCallingUpdateTeam() {
        Team team = testContext.team();

        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        ModelProvider provider =
                new ModelProvider("Legacy Provider", ProviderType.OPENAI, "sk-fake-key", dockerAccessibleBaseUrl);
        // No models are persisted on the provider, and provisionModel/ensureModelRegistered is
        // never called for it -- this provider+model combination is entirely unknown to LiteLLM.
        provider.setModels(List.of());
        provider.setTeam(team);
        provider = modelProviderRepository.saveAndFlush(provider);

        // Set team's ingestion/embedding fields directly, bypassing TeamService.updateTeam()
        // entirely -- e.g. legacy data from before this fix existed.
        team.setIngestionProvider(provider);
        team.setIngestionModel("gpt-4o");
        team.setEmbeddingProvider(provider);
        team.setEmbeddingModel("text-embedding-3-small");
        team = teamRepository.saveAndFlush(team);

        assertThat(liteLLMProvisioningService.verifyModelRegistered(provider, "text-embedding-3-small"))
                .as("Sanity check: embedding model must not be registered with LiteLLM yet")
                .isFalse();

        Repository repository = createRepositoryForTeam(team);

        runIngestionAndAssertRealEmbeddingSucceeded(repository);
    }

    private void runIngestionAndAssertRealEmbeddingSucceeded(Repository repository) {
        fakeChatModel.addMatchers(ingestionPipelineMatchers());

        // The wiki page generated by the FakeChatModel matchers below contains "# User
        // Management" -- that's the content SemanticIndexingService will chunk and send to the
        // real embedding model.
        float[] expectedVector = new float[] {0.11f, 0.22f, 0.33f, 0.44f};
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("User Management")
                .jsonResponse(LlmResponseBuilders.openAiEmbedding(expectedVector))
                .build());

        IngestionBatch batch = ingestionCoordinatorService.triggerIngestion(repository.getId());

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    IngestionBatch finishedBatch =
                            ingestionBatchRepository.findById(batch.getId()).orElseThrow();
                    assertThat(finishedBatch.getStatus())
                            .as("Ingestion batch status. If FAILED, error message: %s", finishedBatch.getErrorMessage())
                            .isEqualTo(IngestionStatus.SUCCESS);
                });

        // The embeddings must have been produced by the REAL EmbeddingModelFactory through the
        // real LiteLLM proxy -- i.e. the request actually reached WireMock as the mocked upstream
        // provider. This is the assertion that would fail with a LiteLLM "Invalid model name"
        // error if the embedding model auto-provisioning fix regressed.
        List<CtxEmbedding> embeddings = ctxEmbeddingRepository.findAll();
        assertThat(embeddings).isNotEmpty();
        assertThat(embeddings.getFirst().getEmbedding()).containsExactly(expectedVector);
        assertThat(wireMockLlmServer.getTotalMatchedRequests())
                .as("Embedding requests should have reached WireMock through the real LiteLLM proxy")
                .isGreaterThanOrEqualTo(1);
    }
}
