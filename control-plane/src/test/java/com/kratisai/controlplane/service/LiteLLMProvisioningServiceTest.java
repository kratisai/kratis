package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderModel;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class LiteLLMProvisioningServiceTest {

    @Mock
    private LiteLLMClient liteLLMClient;

    @Mock
    private ModelProviderRepository modelProviderRepository;

    @Mock
    private TeamRepository teamRepository;

    private LiteLLMProvisioningService provisioningService;

    private Team testTeam;
    private ModelProvider testProvider;

    @BeforeEach
    void setUp() {
        provisioningService =
                new LiteLLMProvisioningService(liteLLMClient, modelProviderRepository, teamRepository, true);

        testTeam = new Team();
        testTeam.setId(UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        testTeam.setName("Test Team");

        testProvider = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-test-key", null);
        testProvider.setTeam(testTeam);
        testProvider.setModels(List.of(
                new ProviderModel("gpt-4", ModelKind.CHAT), new ProviderModel("gpt-3.5-turbo", ModelKind.CHAT)));
    }

    @Test
    void verifyModelRegistered_withModelName_returnsTrue_whenModelExists() {
        // Given: LiteLLM v2 endpoint returns the model for the targeted lookup
        String modelName = "gpt-4";
        String expectedLiteLLMName = provisioningService.buildLiteLLMModelName(testProvider, modelName);

        ListModelsV2Response response = new ListModelsV2Response(
                List.of(new ModelConfig(expectedLiteLLMName, new LiteLLMParams("gpt-4", "sk-key", "openai", null))));

        when(liteLLMClient.listModelByName(expectedLiteLLMName)).thenReturn(response);

        // When
        boolean result = provisioningService.verifyModelRegistered(testProvider, modelName);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void verifyModelRegistered_withModelName_returnsFalse_whenModelDoesNotExist() {
        // Given: LiteLLM v2 endpoint returns empty for the targeted lookup
        String modelName = "gpt-4-turbo";
        String expectedLiteLLMName = provisioningService.buildLiteLLMModelName(testProvider, modelName);

        ListModelsV2Response response = new ListModelsV2Response(List.of());

        when(liteLLMClient.listModelByName(expectedLiteLLMName)).thenReturn(response);

        // When
        boolean result = provisioningService.verifyModelRegistered(testProvider, modelName);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void verifyModelRegistered_withModelName_returnsFalse_whenLiteLLMReturnsNull() {
        // Given: LiteLLM v2 endpoint returns null data
        String modelName = "gpt-4";
        String expectedLiteLLMName = provisioningService.buildLiteLLMModelName(testProvider, modelName);
        ListModelsV2Response response = new ListModelsV2Response(null);

        when(liteLLMClient.listModelByName(expectedLiteLLMName)).thenReturn(response);

        // When
        boolean result = provisioningService.verifyModelRegistered(testProvider, modelName);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void verifyModelRegistered_withModelName_returnsFalse_whenLiteLLMReturnsEmptyList() {
        // Given: LiteLLM v2 endpoint returns an empty list
        String modelName = "gpt-4";
        String expectedLiteLLMName = provisioningService.buildLiteLLMModelName(testProvider, modelName);
        ListModelsV2Response response = new ListModelsV2Response(List.of());

        when(liteLLMClient.listModelByName(expectedLiteLLMName)).thenReturn(response);

        // When
        boolean result = provisioningService.verifyModelRegistered(testProvider, modelName);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void provisionModel_setsCorrectModelInfoMode_forChatAndEmbeddingModels() {
        // Given: A provider with both chat and embedding models
        ModelProvider provider = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-test-key", null);
        provider.setTeam(testTeam);
        provider.setModels(List.of(
                new ProviderModel("gpt-4", ModelKind.CHAT),
                new ProviderModel("text-embedding-3-small", ModelKind.EMBEDDING)));

        // When
        provisioningService.provisionModel(provider);

        // Then: Verify addModel was called twice (once for each model)
        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient, times(2)).addModel(requestCaptor.capture());

        List<AddModelRequest> requests = requestCaptor.getAllValues();
        assertThat(requests).hasSize(2);

        // Verify chat model has mode "chat"
        AddModelRequest chatRequest = requests.getFirst();
        assertThat(chatRequest.modelInfo()).isNotNull();
        assertThat(chatRequest.modelInfo().mode()).isEqualTo("chat");

        // Verify embedding model has mode "embedding"
        AddModelRequest embeddingRequest = requests.get(1);
        assertThat(embeddingRequest.modelInfo()).isNotNull();
        assertThat(embeddingRequest.modelInfo().mode()).isEqualTo("embedding");
    }

    @Test
    void provisionModel_bedrock_shouldRegisterAsOpenAiCompatibleWithBearerToken() {
        ModelProvider provider = new ModelProvider(
                "Bedrock",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-runtime.us-east-1.amazonaws.com");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("us.anthropic.claude-sonnet-4-6", ModelKind.CHAT)));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        LiteLLMParams params = requestCaptor.getValue().litellmParams();
        assertThat(params.customLlmProvider()).isEqualTo("openai");
        assertThat(params.apiKey()).isEqualTo("bedrock-bearer-token");
        assertThat(params.apiBase()).isEqualTo("https://bedrock-runtime.us-east-1.amazonaws.com/v1");
    }

    @Test
    void provisionModel_bedrock_shouldAttachRegionalPricingFromLiteLLMCostMap() {
        ModelProvider provider = new ModelProvider(
                "Bedrock MiniMax",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-mantle.eu-west-2.api.aws");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("minimax.minimax-m2.5", ModelKind.CHAT)));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("bedrock/eu-west-2/minimax.minimax-m2.5", new ModelCostEntry(4.7e-07, 1.86e-06)));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        ModelInfo modelInfo = requestCaptor.getValue().modelInfo();
        assertThat(modelInfo.mode()).isEqualTo("chat");
        assertThat(modelInfo.inputCostPerToken()).isEqualTo(4.7e-07);
        assertThat(modelInfo.outputCostPerToken()).isEqualTo(1.86e-06);
    }

    @Test
    void provisionModel_bedrock_shouldFallBackToGlobalCostEntry_whenRegionEntryIsAbsent() {
        ModelProvider provider = new ModelProvider(
                "Bedrock MiniMax",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-runtime.us-east-1.amazonaws.com");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("minimax.minimax-m2.1", ModelKind.CHAT)));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("minimax.minimax-m2.1", new ModelCostEntry(3.0e-07, 1.2e-06)));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        ModelInfo modelInfo = requestCaptor.getValue().modelInfo();
        assertThat(modelInfo.inputCostPerToken()).isEqualTo(3.0e-07);
        assertThat(modelInfo.outputCostPerToken()).isEqualTo(1.2e-06);
    }

    @Test
    void provisionModel_bedrock_shouldResolveWithoutRegionKey_whenBaseUrlHasNoRegion() {
        ModelProvider provider = new ModelProvider(
                "Bedrock MiniMax", ProviderType.BEDROCK, "bedrock-bearer-token", "https://bedrock-proxy.example.com");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("minimax.minimax-m2.5", ModelKind.CHAT)));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("minimax.minimax-m2.5", new ModelCostEntry(3.0e-07, 1.2e-06)));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        assertThat(requestCaptor.getValue().modelInfo().inputCostPerToken()).isEqualTo(3.0e-07);
    }

    @Test
    void provisionModel_bedrock_shouldNotAttachPricing_whenCostMapHasNoEntry() {
        ModelProvider provider = new ModelProvider(
                "Bedrock",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-runtime.us-east-1.amazonaws.com");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("us.anthropic.claude-sonnet-4-6", ModelKind.CHAT)));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("bedrock/eu-west-2/minimax.minimax-m2.5", new ModelCostEntry(4.7e-07, 1.86e-06)));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        ModelInfo modelInfo = requestCaptor.getValue().modelInfo();
        assertThat(modelInfo.mode()).isEqualTo("chat");
        assertThat(modelInfo.inputCostPerToken()).isNull();
        assertThat(modelInfo.outputCostPerToken()).isNull();
    }

    @Test
    void provisionModel_shouldFetchCostMapOncePerProvisioningPass() {
        ModelProvider provider = new ModelProvider(
                "Bedrock",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-runtime.us-east-1.amazonaws.com");
        provider.setTeam(testTeam);
        provider.setModels(List.of(
                new ProviderModel("minimax.minimax-m2.5", ModelKind.CHAT),
                new ProviderModel("qwen.qwen3-coder-480b-a35b-instruct", ModelKind.CHAT)));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("minimax.minimax-m2.5", new ModelCostEntry(3.0e-07, 1.2e-06)));

        provisioningService.provisionModel(provider);

        verify(liteLLMClient, times(1)).modelCostMap();
        verify(liteLLMClient, times(2)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void provisionModel_nonBedrockProvider_shouldNotAttachPricingOrFetchCostMap() {
        provisioningService.provisionModel(testProvider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient, times(2)).addModel(requestCaptor.capture());
        for (AddModelRequest request : requestCaptor.getAllValues()) {
            assertThat(request.modelInfo().inputCostPerToken()).isNull();
            assertThat(request.modelInfo().outputCostPerToken()).isNull();
        }
        verify(liteLLMClient, times(0)).modelCostMap();
    }

    @Test
    void provisionModel_shouldPassBaseModelToLiteLLM() {
        ModelProvider provider = new ModelProvider(
                "Azure", ProviderType.AZURE_OPENAI, "azure-key", "https://my-resource.openai.azure.com");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("gpt-4o-deployment", "gpt-4o", ModelKind.CHAT)));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        assertThat(requestCaptor.getValue().litellmParams().baseModel()).isEqualTo("gpt-4o");
    }

    @Test
    void provisionModel_bedrock_shouldStillProvision_whenCostMapIsUnavailable() {
        ModelProvider provider = new ModelProvider(
                "Bedrock MiniMax",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-mantle.eu-west-2.api.aws");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("minimax.minimax-m2.5", ModelKind.CHAT)));
        when(liteLLMClient.modelCostMap()).thenThrow(new ResourceAccessException("connection refused"));

        provisioningService.provisionModel(provider);

        ArgumentCaptor<AddModelRequest> requestCaptor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(requestCaptor.capture());
        ModelInfo modelInfo = requestCaptor.getValue().modelInfo();
        assertThat(modelInfo.mode()).isEqualTo("chat");
        assertThat(modelInfo.inputCostPerToken()).isNull();
        assertThat(modelInfo.outputCostPerToken()).isNull();
    }

    @Test
    void provisionModel_replacesStaleDeploymentByModelId() {
        ModelProvider provider = new ModelProvider(
                "AWS Bedrock",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-mantle.eu-west-2.api.aws");
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("minimax.minimax-m2", ModelKind.CHAT)));
        String alias = provisioningService.buildLiteLLMModelName(provider, "minimax.minimax-m2");
        // A registration left behind when BEDROCK still mapped to LiteLLM's native bedrock provider.
        ModelConfig stale = new ModelConfig(
                alias,
                new LiteLLMParams(
                        "minimax.minimax-m2",
                        "bedrock-bearer-token",
                        "bedrock",
                        "https://bedrock-mantle.eu-west-2.api.aws/v1"),
                new ModelInfo("stale-deployment-id", "chat", null, null));
        when(liteLLMClient.listModelByName(alias))
                .thenReturn(new ListModelsV2Response(List.of(stale)))
                .thenReturn(new ListModelsV2Response(List.of()));

        provisioningService.provisionModel(provider);

        verify(liteLLMClient).deleteModel(new DeleteModelRequest("stale-deployment-id"));
        verify(liteLLMClient).addModel(org.mockito.ArgumentMatchers.any(AddModelRequest.class));
    }

    @Test
    void provisionModel_deletesEveryDuplicateDeploymentForTheModelGroup() {
        ModelProvider provider = new ModelProvider("AWS Bedrock", ProviderType.BEDROCK, "key", null);
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("minimax.minimax-m2", ModelKind.CHAT)));
        String alias = provisioningService.buildLiteLLMModelName(provider, "minimax.minimax-m2");
        LiteLLMParams params = new LiteLLMParams("minimax.minimax-m2", "key", "openai", null);
        when(liteLLMClient.listModelByName(alias))
                .thenReturn(new ListModelsV2Response(List.of(
                        new ModelConfig(alias, params, new ModelInfo("id-1", "chat", null, null)),
                        new ModelConfig(alias, params, new ModelInfo("id-2", "chat", null, null)))))
                .thenReturn(new ListModelsV2Response(
                        List.of(new ModelConfig(alias, params, new ModelInfo("id-3", "chat", null, null)))))
                .thenReturn(new ListModelsV2Response(List.of()));

        provisioningService.provisionModel(provider);

        verify(liteLLMClient).deleteModel(new DeleteModelRequest("id-1"));
        verify(liteLLMClient).deleteModel(new DeleteModelRequest("id-2"));
        verify(liteLLMClient).deleteModel(new DeleteModelRequest("id-3"));
    }

    @Test
    void provisionModel_ignoresDeploymentsThatDoNotMatchTheModelGroup() {
        ModelProvider provider = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-test-key", null);
        provider.setTeam(testTeam);
        provider.setModels(List.of(new ProviderModel("gpt-4", ModelKind.CHAT)));
        String alias = provisioningService.buildLiteLLMModelName(provider, "gpt-4");
        ModelConfig other = new ModelConfig(
                "another-alias",
                new LiteLLMParams("gpt-4", "sk-test-key", "openai", null),
                new ModelInfo("other-id", "chat", null, null));
        when(liteLLMClient.listModelByName(alias)).thenReturn(new ListModelsV2Response(List.of(other)));

        provisioningService.provisionModel(provider);

        verify(liteLLMClient, times(0)).deleteModel(org.mockito.ArgumentMatchers.any(DeleteModelRequest.class));
        verify(liteLLMClient).addModel(org.mockito.ArgumentMatchers.any(AddModelRequest.class));
    }

    @Test
    void removeModel_deletesDeploymentsByModelId() {
        String alias = provisioningService.buildLiteLLMModelName(testProvider, "gpt-4");
        ModelConfig config = new ModelConfig(
                alias,
                new LiteLLMParams("gpt-4", "sk-test-key", "openai", null),
                new ModelInfo("deployment-9", "chat", null, null));
        when(liteLLMClient.listModelByName(alias))
                .thenReturn(new ListModelsV2Response(List.of(config)))
                .thenReturn(new ListModelsV2Response(List.of()));

        provisioningService.removeModel(testProvider);

        verify(liteLLMClient).deleteModel(new DeleteModelRequest("deployment-9"));
    }

    @Test
    void provisionModel_setsModelInfoModeToChat_forChatModels() {
        // Given: A provider with a CHAT model
        ModelProvider chatProvider = new ModelProvider("Chat Provider", ProviderType.OPENAI, "sk-test-key", null);
        chatProvider.setTeam(testTeam);
        chatProvider.setModels(List.of(new ProviderModel("gpt-4", ModelKind.CHAT)));

        // When: Provisioning the model
        provisioningService.provisionModel(chatProvider);

        // Then: Verify addModel was called with model_info.mode = "chat"
        ArgumentCaptor<AddModelRequest> captor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient, times(1)).addModel(captor.capture());

        AddModelRequest request = captor.getValue();
        assertThat(request.modelInfo()).isNotNull();
        assertThat(request.modelInfo().mode()).isEqualTo("chat");
    }

    @Test
    void provisionModel_setsModelInfoModeToEmbedding_forEmbeddingModels() {
        // Given: A provider with an EMBEDDING model
        ModelProvider embeddingProvider =
                new ModelProvider("Embedding Provider", ProviderType.OPENAI, "sk-test-key", null);
        embeddingProvider.setTeam(testTeam);
        embeddingProvider.setModels(List.of(new ProviderModel("text-embedding-3-small", ModelKind.EMBEDDING)));

        // When: Provisioning the model
        provisioningService.provisionModel(embeddingProvider);

        // Then: Verify addModel was called with model_info.mode = "embedding"
        ArgumentCaptor<AddModelRequest> captor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient, times(1)).addModel(captor.capture());

        AddModelRequest request = captor.getValue();
        assertThat(request.modelInfo()).isNotNull();
        assertThat(request.modelInfo().mode()).isEqualTo("embedding");
    }

    @Test
    void provisionModel_setsCorrectMode_forMixedChatAndEmbeddingModels() {
        // Given: A provider with both CHAT and EMBEDDING models
        ModelProvider mixedProvider = new ModelProvider("Mixed Provider", ProviderType.OPENAI, "sk-test-key", null);
        mixedProvider.setTeam(testTeam);
        mixedProvider.setModels(List.of(
                new ProviderModel("gpt-4", ModelKind.CHAT),
                new ProviderModel("text-embedding-3-small", ModelKind.EMBEDDING)));

        // When: Provisioning the models
        provisioningService.provisionModel(mixedProvider);

        // Then: Verify addModel was called twice with correct modes
        ArgumentCaptor<AddModelRequest> captor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient, times(2)).addModel(captor.capture());

        List<AddModelRequest> requests = captor.getAllValues();
        assertThat(requests).hasSize(2);

        // Find the chat model request
        AddModelRequest chatRequest = requests.stream()
                .filter(r -> r.modelInfo().mode().equals("chat"))
                .findFirst()
                .orElseThrow();
        assertThat(chatRequest.modelInfo().mode()).isEqualTo("chat");

        // Find the embedding model request
        AddModelRequest embeddingRequest = requests.stream()
                .filter(r -> r.modelInfo().mode().equals("embedding"))
                .findFirst()
                .orElseThrow();
        assertThat(embeddingRequest.modelInfo().mode()).isEqualTo("embedding");
    }

    @Test
    void reconcileOnStartup_provisionsTeamsIngestionAndEmbeddingModels_whenMissingFromLiteLLM() {
        // Given: a team whose ingestion/embedding model is NOT part of its provider's persisted
        // `models` list at all (e.g. picked from live model discovery, or predating this
        // self-healing logic), and LiteLLM has no models registered yet.
        testTeam.setIngestionProvider(testProvider);
        testTeam.setIngestionModel("gpt-4");
        testTeam.setEmbeddingProvider(testProvider);
        testTeam.setEmbeddingModel("text-embedding-3-small");
        testProvider.setModels(List.of());

        when(liteLLMClient.listModels()).thenReturn(new ListModelsResponse(List.of()));
        when(modelProviderRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of(testTeam));

        // When
        provisioningService.reconcileOnStartup();

        // Then: both the ingestion (chat) and embedding models get provisioned to LiteLLM.
        ArgumentCaptor<AddModelRequest> captor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient, times(2)).addModel(captor.capture());

        List<AddModelRequest> requests = captor.getAllValues();
        assertThat(requests)
                .anyMatch(r -> r.modelInfo().mode().equals("chat"))
                .anyMatch(r -> r.modelInfo().mode().equals("embedding"));

        // And: the provider's persisted model list is backfilled with both models.
        assertThat(testProvider.getModels())
                .anyMatch(pm -> pm.getModelName().equals("gpt-4") && pm.getKind() == ModelKind.CHAT)
                .anyMatch(pm ->
                        pm.getModelName().equals("text-embedding-3-small") && pm.getKind() == ModelKind.EMBEDDING);
    }

    @Test
    void reconcileOnStartup_skipsTeamModels_whenAlreadyRegisteredInLiteLLM() {
        // Given: the team's ingestion/embedding models are already registered with LiteLLM.
        testTeam.setIngestionProvider(testProvider);
        testTeam.setIngestionModel("gpt-4");
        testTeam.setEmbeddingProvider(testProvider);
        testTeam.setEmbeddingModel("text-embedding-3-small");
        testProvider.setModels(List.of(
                new ProviderModel("gpt-4", ModelKind.CHAT),
                new ProviderModel("text-embedding-3-small", ModelKind.EMBEDDING)));

        String chatLiteLLMName = provisioningService.buildLiteLLMModelName(testProvider, "gpt-4");
        String embeddingLiteLLMName = provisioningService.buildLiteLLMModelName(testProvider, "text-embedding-3-small");
        when(liteLLMClient.listModels())
                .thenReturn(new ListModelsResponse(List.of(
                        new ModelConfig(chatLiteLLMName, new LiteLLMParams("gpt-4", "sk-key", "openai", null)),
                        new ModelConfig(
                                embeddingLiteLLMName,
                                new LiteLLMParams("text-embedding-3-small", "sk-key", "openai", null)))));
        when(modelProviderRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of(testTeam));

        // When
        provisioningService.reconcileOnStartup();

        // Then: no re-provisioning is necessary since both models are already registered.
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconcileOnStartup_reprovisionsBedrockModel_whenLiteLLMCanPriceIt() {
        ModelProvider bedrockProvider = new ModelProvider(
                "Bedrock MiniMax",
                ProviderType.BEDROCK,
                "bedrock-bearer-token",
                "https://bedrock-mantle.eu-west-2.api.aws");
        bedrockProvider.setTeam(testTeam);
        bedrockProvider.setModels(List.of(new ProviderModel("minimax.minimax-m2.5", ModelKind.CHAT)));
        String litellmName = provisioningService.buildLiteLLMModelName(bedrockProvider, "minimax.minimax-m2.5");

        when(liteLLMClient.listModels())
                .thenReturn(new ListModelsResponse(List.of(new ModelConfig(
                        litellmName,
                        new LiteLLMParams("minimax.minimax-m2.5", "bedrock-bearer-token", "openai", null)))));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("bedrock/eu-west-2/minimax.minimax-m2.5", new ModelCostEntry(4.7e-07, 1.86e-06)));
        when(modelProviderRepository.findAll()).thenReturn(List.of(bedrockProvider));
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of());

        provisioningService.reconcileOnStartup();

        ArgumentCaptor<AddModelRequest> captor = ArgumentCaptor.forClass(AddModelRequest.class);
        verify(liteLLMClient).addModel(captor.capture());
        assertThat(captor.getValue().modelInfo().inputCostPerToken()).isEqualTo(4.7e-07);
        assertThat(captor.getValue().modelInfo().outputCostPerToken()).isEqualTo(1.86e-06);
    }

    @Test
    void reconcileOnStartup_doesNotReprovisionAlreadyRegisteredNonCatalogModels() {
        // Given: a non-cataloged model already registered in LiteLLM.
        String litellmName = provisioningService.buildLiteLLMModelName(testProvider, "gpt-4");
        when(liteLLMClient.listModels())
                .thenReturn(new ListModelsResponse(List.of(
                        new ModelConfig(litellmName, new LiteLLMParams("gpt-4", "sk-key", "openai", null)),
                        new ModelConfig(
                                provisioningService.buildLiteLLMModelName(testProvider, "gpt-3.5-turbo"),
                                new LiteLLMParams("gpt-3.5-turbo", "sk-key", "openai", null)))));
        when(modelProviderRepository.findAll()).thenReturn(List.of(testProvider));
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of());

        // When
        provisioningService.reconcileOnStartup();

        // Then: no re-provisioning happens.
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconcileOnStartup_skipsTeamsWithNoIngestionOrEmbeddingModelConfigured() {
        // Given: a team with no ingestion/embedding provider or model configured at all.
        when(liteLLMClient.listModels()).thenReturn(new ListModelsResponse(List.of()));
        when(modelProviderRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of(testTeam));

        // When / Then: no exception, and no provisioning calls are made.
        provisioningService.reconcileOnStartup();
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconcileOnStartup_treatsLiteLLMEmptyModelListErrorAsNoModels_andStillProvisions() {
        // Given: LiteLLM has no models configured yet, so `/model/info` returns a 500 (rather
        // than an empty list) with its "Model List not loaded in" error body.
        when(liteLLMClient.listModels()).thenThrow(emptyModelListError());

        testTeam.setIngestionProvider(testProvider);
        testTeam.setIngestionModel("gpt-4");
        testProvider.setModels(List.of());
        when(modelProviderRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of(testTeam));

        // When
        provisioningService.reconcileOnStartup();

        // Then: reconciliation still runs to completion (not treated as LiteLLM being
        // unavailable) and provisions the team's ingestion model.
        verify(liteLLMClient, times(1)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconcileOnStartup_stillTreatsOtherServerErrors_asLiteLLMUnavailable() {
        // Given: a genuine LiteLLM server error unrelated to an empty model list.
        when(liteLLMClient.listModels())
                .thenThrow(HttpServerErrorException.create(
                        HttpStatusCode.valueOf(500),
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        "{\"detail\":\"boom\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        // When / Then: still treated as a failed reconciliation attempt, not swallowed.
        provisioningService.reconcileOnStartup();
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());

        // And: the retry mechanism still considers this a failure that needs retrying.
        provisioningService.retryReconciliation();
        verify(liteLLMClient, times(2)).listModels();
    }

    @Test
    void verifyModelRegistered_returnsFalse_whenLiteLLMHasNoModelsConfiguredYet() {
        // Given: LiteLLM's `/model/info` 500s with the empty-model-list error.
        when(liteLLMClient.listModels()).thenThrow(emptyModelListError());

        // When / Then: treated as "not registered" rather than throwing.
        assertThat(provisioningService.verifyModelRegistered(testProvider)).isFalse();
    }

    private static HttpServerErrorException emptyModelListError() {
        String body = "{\"detail\":{\"error\":\"LLM Model List not loaded in. Make sure you passed models in your "
                + "config.yaml or on the LiteLLM Admin UI. - https://docs.litellm.ai/docs/proxy/configs\"}}";
        return HttpServerErrorException.create(
                HttpStatusCode.valueOf(500),
                "Internal Server Error",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void reconcileOnStartup_doesNotThrow_whenLiteLLMUnavailable() {
        // Given: LiteLLM is not reachable (e.g. local dev without the LiteLLM container).
        when(liteLLMClient.listModels())
                .thenThrow(new ResourceAccessException("Connect to http://localhost:4000 failed: Connection refused"));

        // When / Then: reconciliation is skipped gracefully so the application can still start.
        provisioningService.reconcileOnStartup();
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryReconciliation_retriesAndSucceeds_whenLiteLLMRecovers() {
        // Given: LiteLLM is unreachable during the first reconciliation attempt...
        when(liteLLMClient.listModels())
                .thenThrow(new ResourceAccessException("Connect to http://localhost:4000 failed: Connection refused"));

        provisioningService.reconcileOnStartup();
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());

        // ...and becomes reachable before the scheduled retry runs (Docker Compose may still
        // have been booting the LiteLLM gateway).
        testTeam.setIngestionProvider(testProvider);
        testTeam.setIngestionModel("gpt-4");
        testProvider.setModels(List.of());
        // doReturn (rather than when/thenReturn) because invoking listModels() would re-throw the earlier stub
        doReturn(new ListModelsResponse(List.of())).when(liteLLMClient).listModels();
        when(modelProviderRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of(testTeam));

        // When: the scheduled retry fires
        provisioningService.retryReconciliation();

        // Then: the model gets provisioned on the second attempt.
        verify(liteLLMClient, times(1)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryReconciliation_doesNothing_whenReconciliationAlreadySucceeded() {
        // Given: startup reconciliation already completed successfully.
        when(liteLLMClient.listModels()).thenReturn(new ListModelsResponse(List.of()));
        when(modelProviderRepository.findAll()).thenReturn(List.of());
        when(teamRepository.findAllWithIngestionAndEmbeddingProviders()).thenReturn(List.of());

        provisioningService.reconcileOnStartup();

        // When: a scheduled retry fires afterwards
        provisioningService.retryReconciliation();

        // Then: no further LiteLLM interaction happens.
        verify(liteLLMClient, times(1)).listModels();
        verify(liteLLMClient, times(0)).addModel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryReconciliation_givesUpAfterMaxFailedAttempts() {
        // Given: LiteLLM never becomes reachable.
        when(liteLLMClient.listModels())
                .thenThrow(new ResourceAccessException("Connect to http://localhost:4000 failed: Connection refused"));

        // When: the maximum number of reconciliation attempts has been exhausted
        for (int i = 0; i < 12; i++) {
            provisioningService.reconcileOnStartup();
        }
        provisioningService.retryReconciliation();

        // Then: no further attempt is made.
        verify(liteLLMClient, times(12)).listModels();

        provisioningService.retryReconciliation();
        verify(liteLLMClient, times(12)).listModels();
    }
}
