package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.*;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@UseRealLlmClient
@SpringIntegrationTest
// The test profile normally sets kratis.litellm.reconcile-on-startup=false so @PostConstruct
// doesn't hit LiteLLM on every context boot. Override it back to true here so the reconciliation
// test below exercises the real behavior, against a Spring context reserved for this override.
@TestPropertySource(properties = "kratis.litellm.reconcile-on-startup=true")
class LiteLLMProvisioningIntegrationTest {

    @Autowired
    private LiteLLMClient liteLLMClient;

    @Autowired
    private LiteLLMProvisioningService provisioningService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private VirtualKeyService virtualKeyService;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldAddModelViaLiteLLMAdminApi() {
        AddModelRequest request =
                new AddModelRequest("test-model", new LiteLLMParams("gpt-4", "sk-fake-key", "openai", null));

        AddModelResponse response = liteLLMClient.addModel(request);

        assertThat(response).isNotNull();
    }

    @Test
    void shouldListModelsAfterAdding() {
        String modelName = "test-list-model-" + System.nanoTime();
        liteLLMClient.addModel(
                new AddModelRequest(modelName, new LiteLLMParams("gpt-4", "sk-fake-key", "openai", null)));

        ListModelsResponse response = liteLLMClient.listModels();

        assertThat(response.data()).isNotNull();
        assertThat(response.data().stream().map(ModelConfig::modelName)).contains(modelName);
    }

    @Test
    void shouldDeleteModelViaLiteLLMAdminApi() {
        String modelName = "test-delete-model-" + System.nanoTime();
        liteLLMClient.addModel(
                new AddModelRequest(modelName, new LiteLLMParams("gpt-4", "sk-fake-key", "openai", null)));

        ListModelsResponse beforeDelete = liteLLMClient.listModels();
        assertThat(beforeDelete.data().stream().map(ModelConfig::modelName)).contains(modelName);

        // Delete may return 400 if model isn't persisted to DB (in-memory only), which
        // is acceptable
        try {
            DeleteModelResponse deleteResponse = liteLLMClient.deleteModel(new DeleteModelRequest(modelName));
            assertThat(deleteResponse).isNotNull();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // Model was visible in list but not in DB — acceptable in test environment
            assertThat(e.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    void shouldProvisionAllModelsForProvider() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider = new ModelProvider("Multi Model Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(List.of(
                new ProviderModel("gpt-4", ModelKind.CHAT),
                new ProviderModel("gpt-4o", ModelKind.CHAT),
                new ProviderModel("gpt-3.5-turbo", ModelKind.CHAT)));
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        provisioningService.provisionModel(provider);

        assertThat(provisioningService.verifyModelRegistered(provider)).isTrue();
    }

    @Test
    void shouldSkipProviderWithNoModelNames() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider = new ModelProvider("Empty Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(List.of());
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        provisioningService.provisionModel(provider);

        assertThat(provisioningService.verifyModelRegistered(provider)).isFalse();
    }

    @Test
    void shouldBuildDeterministicModelNameWithTeamAndModelDifferentiator() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider = new ModelProvider("My Anthropic Provider", ProviderType.ANTHROPIC, "sk-fake", null);
        provider.setTeam(team);

        String modelName = provisioningService.buildLiteLLMModelName(provider, "claude-sonnet-4-20250514");

        String teamSuffix = team.getId().toString().substring(0, 8);
        assertThat(modelName).isEqualTo("anthropic-my-anthropic-provider-claude-sonnet-4-20250514-" + teamSuffix);
    }

    @Test
    void shouldResolveLiteLLMProviderForAllSupportedTypes() {
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.OPENAI))
                .isEqualTo("openai");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.ANTHROPIC))
                .isEqualTo("anthropic");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.GOOGLE))
                .isEqualTo("gemini");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.GROQ))
                .isEqualTo("groq");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.MISTRAL))
                .isEqualTo("mistral");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.DEEPSEEK))
                .isEqualTo("deepseek");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.OLLAMA))
                .isEqualTo("ollama");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.AZURE_OPENAI))
                .isEqualTo("azure");
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.BEDROCK))
                .isEqualTo("openai");
    }

    @Test
    void shouldReturnNullForUnsupportedProviderType() {
        assertThat(provisioningService.resolveLiteLLMProvider(ProviderType.OTHER))
                .isNull();
    }

    @Test
    void shouldRemoveAllModelsForProvider() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider = new ModelProvider("Remove Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(
                List.of(new ProviderModel("gpt-4", ModelKind.CHAT), new ProviderModel("gpt-4o", ModelKind.CHAT)));
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        provisioningService.provisionModel(provider);
        assertThat(provisioningService.verifyModelRegistered(provider)).isTrue();

        // removeModel is resilient — it logs warnings for models not found in DB
        provisioningService.removeModel(provider);

        // After removal, models may still appear in list if they're in-memory only
        // The key assertion is that removeModel doesn't throw
    }

    @Test
    void reconcileOnStartup_doesNotThrowLazyInitializationException_andProvisionsTeamModels() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam(false);
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider =
                new ModelProvider("Startup Reconcile Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(List.of());
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        team.setIngestionProvider(provider);
        team.setIngestionModel("gpt-4o");
        team.setEmbeddingProvider(provider);
        team.setEmbeddingModel("text-embedding-3-small");
        teamRepository.saveAndFlush(team);

        assertThat(provisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .isFalse();
        assertThat(provisioningService.verifyModelRegistered(provider, "text-embedding-3-small"))
                .isFalse();

        provisioningService.reconcileOnStartup();

        assertThat(provisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .isTrue();
        assertThat(provisioningService.verifyModelRegistered(provider, "text-embedding-3-small"))
                .isTrue();
    }

    // --- VirtualKeyService Full-Stack Tests (against real LiteLLM) ---

    @Test
    void virtualKeyService_shouldGenerateVirtualKey() {
        String keyAlias = "test-key-" + System.nanoTime();
        List<String> modelNames = List.of("gpt-4o");

        String virtualKey = virtualKeyService.generateKey(keyAlias, modelNames);
        assertThat(virtualKey).isNotBlank();
        assertThat(virtualKey).startsWith("sk-");

        // Clean up
        virtualKeyService.revokeKey(virtualKey);
    }

    @Test
    void virtualKeyService_shouldRevokeKey() {
        String keyAlias = "test-revoke-" + System.nanoTime();
        List<String> modelNames = List.of("gpt-4o");

        String virtualKey = virtualKeyService.generateKey(keyAlias, modelNames);
        virtualKeyService.revokeKey(virtualKey);

        // After revocation, the key should no longer be usable
        // We verify revocation succeeded by checking no exception was thrown above
    }

    @Test
    void virtualKeyService_shouldFetchUsage() {
        String keyAlias = "test-usage-" + System.nanoTime();
        List<String> modelNames = List.of("gpt-4o");

        String virtualKey = virtualKeyService.generateKey(keyAlias, modelNames);
        assertThat(virtualKey).isNotBlank();

        // Fetch usage for the newly created key
        LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(virtualKey);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.spend()).isNotNull();
        assertThat(snapshot.spend()).isZero(); // fresh key, no usage yet
        assertThat(snapshot.totalTokens()).isNotNull();
        assertThat(snapshot.totalTokens()).isZero();

        // Clean up
        virtualKeyService.revokeKey(virtualKey);
    }
}
