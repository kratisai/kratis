package com.kratisai.controlplane;

import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.CredentialService;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@TestComponent
public class TestDataFactory {

    @Autowired
    private RepoCredentialRepository repoCredentialRepository;

    @Autowired
    private CredentialService credentialService;

    public static final String DUMMY_PASSWORD = "password_password_123";

    public static final String DEFAULT_CHAT_MODEL = "gpt-4o";

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EnvironmentProviderRepository environmentProviderRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired(required = false)
    private LiteLLMProvisioningService liteLLMProvisioningService;

    public record TestContext(
            Team team, User user, Repository repository, IngestionBatch batch, ModelProvider provider) {}

    public record AuthContext(
            Team team, User user, String accessToken, ModelProvider provider, ExecutionEnvironment defaultSandbox) {
        public AuthContext {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(defaultSandbox, "defaultSandbox");
        }
    }

    private static String dummyRepoUrl;

    private static final Object DUMMY_REPO_LOCK = new Object();

    public static String getOrCreateDummyRepo() {
        synchronized (DUMMY_REPO_LOCK) {
            if (dummyRepoUrl != null) {
                return dummyRepoUrl;
            }
            try {
                Path sourceRepoDir = Files.createTempDirectory("kratis-dummy-repo");
                new ProcessBuilder("git", "init")
                        .directory(sourceRepoDir.toFile())
                        .start()
                        .waitFor();
                Path readme = sourceRepoDir.resolve("readme.md");
                Files.writeString(readme, "# Dummy Repo");
                new ProcessBuilder("git", "add", "readme.md")
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
                dummyRepoUrl = "file://" + sourceRepoDir.toAbsolutePath();
                return dummyRepoUrl;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to create dummy git repository", e);
            }
        }
    }

    @Transactional
    public TestContext createDefaultContext() {
        return createDefaultContext("Test Team", "test-repo");
    }

    @Transactional
    public TestContext createDefaultContext(String teamName, String repoName) {
        return createContextWithCustomRepo(teamName, repoName, getOrCreateDummyRepo());
    }

    @Transactional
    public TestContext createContextWithCustomRepo(String teamName, String repoName, String cloneUrl) {
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Test User");
        user.setPasswordHash("dummy-hash");
        user = userRepository.save(user);

        Team team = new Team(teamName, "Test Description");
        team = teamRepository.save(team);

        TeamMember member = new TeamMember(user, team, "owner");
        teamMemberRepository.save(member);

        // Create default environment provider for the new team
        EnvironmentProvider defaultProvider = new EnvironmentProvider();
        defaultProvider.setTeam(team);
        defaultProvider.setName("Default Docker Provider");
        defaultProvider.setDockerImage("kratis-runner-base:latest");
        environmentProviderRepository.save(defaultProvider);

        ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);
        team.setIngestionProvider(provider);
        team.setIngestionModel("gpt-4o");
        team.setEmbeddingProvider(provider);
        team.setEmbeddingModel("text-embedding-3-small");
        team = teamRepository.save(team);

        Repository repository = new Repository(repoName, cloneUrl, "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        repository = repositoryRepository.save(repository);

        IngestionBatch batch = new IngestionBatch(repository);
        batch.setCommitHash("commit-hash");
        batch.setStatus(IngestionStatus.SUCCESS);
        batch = ingestionBatchRepository.save(batch);

        return new TestContext(team, user, repository, batch, provider);
    }

    /**
     * Points an existing context's ingestion and embedding providers at a WireMock upstream base
     * URL, so OpenAI-compatible traffic flows through the real LiteLLM proxy exactly as in
     * production. The provider type stays OPENAI so Spring AI builds the standard OpenAI clients;
     * only the upstream base URL changes. Re-registers the team's deployments so LiteLLM routes at
     * the new address.
     */
    @Transactional
    public TestContext createContextWithWireMockUpstream(Team team, String wireMockBaseUrl) {
        ModelProvider ingestionProvider = team.getIngestionProvider();
        ingestionProvider.setBaseUrl(wireMockBaseUrl);
        modelProviderRepository.save(ingestionProvider);
        ModelProvider embeddingProvider = team.getEmbeddingProvider();
        embeddingProvider.setBaseUrl(wireMockBaseUrl);
        modelProviderRepository.save(embeddingProvider);
        if (liteLLMProvisioningService != null) {
            liteLLMProvisioningService.ensureModelsRegistered(
                    ingestionProvider, team.getIngestionModel(), embeddingProvider, team.getEmbeddingModel());
        }

        Repository repository = repositoryRepository.findAll().stream()
                .filter(r -> r.getTeam() != null && r.getTeam().getId().equals(team.getId()))
                .findFirst()
                .orElseThrow();
        IngestionBatch batch = ingestionBatchRepository.findAll().stream()
                .filter(b ->
                        b.getRepository() != null && b.getRepository().getId().equals(repository.getId()))
                .findFirst()
                .orElseGet(() -> ingestionBatchRepository.save(new IngestionBatch(repository)));
        return new TestContext(team, null, repository, batch, ingestionProvider);
    }

    @Transactional
    public TestContext createUserAndTeam() {
        return createUserAndTeam(true);
    }

    @Transactional
    public TestContext createUserAndTeam(boolean createProvider) {
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Test User");
        user.setPasswordHash(passwordEncoder.encode(DUMMY_PASSWORD));
        user = userRepository.save(user);

        Team team = new Team("Test Team", "Test Description");
        team = teamRepository.save(team);

        TeamMember member = new TeamMember(user, team, "owner");
        teamMemberRepository.save(member);

        // Create default environment provider for the new team
        EnvironmentProvider defaultProvider = new EnvironmentProvider();
        defaultProvider.setTeam(team);
        defaultProvider.setName("Default Docker Provider");
        defaultProvider.setDockerImage("kratis-runner-base:latest");
        environmentProviderRepository.save(defaultProvider);

        // Create default execution environment for the new team
        ExecutionEnvironment defaultEnv = new ExecutionEnvironment();
        defaultEnv.setTeam(team);
        defaultEnv.setName("Default Sandbox");
        defaultEnv.setType(ExecutionEnvironmentType.SANDBOX);
        defaultEnv.setContainerId("kratis-runner-base:latest");
        defaultEnv.setStatus(com.kratisai.controlplane.model.EnvironmentStatus.DISCONNECTED);
        executionEnvironmentRepository.save(defaultEnv);

        ModelProvider provider = null;
        if (createProvider) {
            // Create default ModelProvider for the new team
            provider = new ModelProvider(
                    "test-provider-" + UUID.randomUUID().toString().substring(0, 8),
                    ProviderType.OPENAI,
                    "dummy-api-key",
                    null);
            provider.setTeam(team);
            provider = modelProviderRepository.save(provider);
            team.setIngestionProvider(provider);
            team.setIngestionModel("gpt-4o");
            team.setEmbeddingProvider(provider);
            team.setEmbeddingModel("text-embedding-3-small");
            team = teamRepository.save(team);
        }

        return new TestContext(team, user, null, null, provider);
    }

    @Transactional
    public AuthContext createAuthenticatedContext() {
        TestContext ctx = createUserAndTeam(true);
        ModelProvider provider = ctx.provider();
        provider.setModels(new ArrayList<>(List.of(new ProviderModel(DEFAULT_CHAT_MODEL, ModelKind.CHAT))));
        provider = modelProviderRepository.save(provider);
        String accessToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
        ExecutionEnvironment defaultSandbox = executionEnvironmentRepository
                .findByTeamId(ctx.team().getId())
                .stream()
                .filter(env -> env.getType() == ExecutionEnvironmentType.SANDBOX)
                .findFirst()
                .orElseThrow();
        return new AuthContext(ctx.team(), ctx.user(), accessToken, provider, defaultSandbox);
    }

    @Transactional
    public AuthContext createProvisionedContext() {
        AuthContext ctx = createAuthenticatedContext();
        provisionModel(ctx.provider());
        return ctx;
    }

    @Transactional
    public ChatEntity createChat(Team team, User user, String title) {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(title, "title");
        return chatRepository.save(new ChatEntity(team, user, title));
    }

    /**
     * Reloads a team with its ingestion and embedding providers initialized, for assertions
     * outside a persistence context.
     */
    @Transactional(readOnly = true)
    public Team getTeamWithProviders(UUID teamId) {
        Team team = teamRepository.findById(teamId).orElseThrow();
        team.getIngestionProvider().getDisplayName();
        team.getEmbeddingProvider().getDisplayName();
        return team;
    }

    public IngestionBatch createBatchForRepository(Repository repository) {
        // Ensure the team has an ingestion provider before creating the batch
        Team team = repository.getTeam();
        if (team.getIngestionProvider() == null) {
            ModelProvider provider = new ModelProvider("test-provider", ProviderType.OPENAI, "dummy-api-key", null);
            provider.setTeam(team);
            provider = modelProviderRepository.save(provider);
            team.setIngestionProvider(provider);
            team.setIngestionModel("gpt-4o");
            teamRepository.save(team);
        }

        IngestionBatch batch = new IngestionBatch(repository);
        batch.setCommitHash("abc1234");
        batch.setStatus(IngestionStatus.SUCCESS);
        return ingestionBatchRepository.save(batch);
    }

    @Transactional
    public RepoCredential createCredential(Team team, String name, CredentialType type, String plainSecret) {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName(name);
        credential.setType(type);
        credential.setEncryptedSecret(credentialService.encrypt(plainSecret));
        return repoCredentialRepository.save(credential);
    }

    @Transactional
    public Repository createRepository(Team team, String name, String url, String branch, RepoCredential credential) {
        Repository repository = new Repository(name, url, branch, RepositoryType.GENERIC);
        repository.setCredential(credential);
        repository.setTeam(team);
        return repositoryRepository.save(repository);
    }

    /**
     * Creates a {@link ModelProvider} for the given team and provisions it in LiteLLM.
     * This mirrors the production flow in {@code ModelProviderService.createModelProvider}
     * which saves the entity and then calls {@code liteLLMProvisioningService.provisionModel}.
     *
     * @param team        the team to associate the provider with
     * @param displayName display name for the provider
     * @param providerType the provider type (e.g. OPENAI)
     * @param apiKey      the API key
     * @param modelNames  list of model names to register
     * @return the saved and provisioned ModelProvider
     */
    @Transactional
    public ModelProvider createModelProviderWithLiteLLM(
            Team team, String displayName, ProviderType providerType, String apiKey, List<String> modelNames) {
        return createModelProviderWithLiteLLM(team, displayName, providerType, apiKey, null, modelNames);
    }

    /**
     * Creates a {@link ModelProvider} for the given team with a custom base URL and provisions it in
     * LiteLLM. The base URL is passed to LiteLLM as the upstream {@code api_base}, allowing tests to
     * route LLM requests to a mock server.
     *
     * @param team        the team to associate the provider with
     * @param displayName display name for the provider
     * @param providerType the provider type (e.g. OPENAI)
     * @param apiKey      the API key
     * @param baseUrl     the upstream base URL (e.g. mock LLM URL), or null for default
     * @param modelNames  list of model names to register
     * @return the saved and provisioned ModelProvider
     */
    @Transactional
    public ModelProvider createModelProviderWithLiteLLM(
            Team team,
            String displayName,
            ProviderType providerType,
            String apiKey,
            String baseUrl,
            List<String> modelNames) {
        ModelProvider provider = new ModelProvider(displayName, providerType, apiKey, baseUrl);
        provider.setTeam(team);
        List<ProviderModel> models = modelNames != null
                ? modelNames.stream()
                        .map(name -> new ProviderModel(name, ModelKind.CHAT))
                        .toList()
                : List.of();
        provider.setModels(models);
        provider = modelProviderRepository.save(provider);
        if (liteLLMProvisioningService != null) {
            liteLLMProvisioningService.provisionModel(provider);
        }
        return provider;
    }

    /**
     * Provisions an already-persisted {@link ModelProvider} in LiteLLM. Useful for tests that
     * create the provider directly via a repository and need to ensure it is registered before
     * launching a sandbox execution.
     */
    public void provisionModel(ModelProvider provider) {
        if (liteLLMProvisioningService != null) {
            liteLLMProvisioningService.provisionModel(provider);
        }
    }
}
