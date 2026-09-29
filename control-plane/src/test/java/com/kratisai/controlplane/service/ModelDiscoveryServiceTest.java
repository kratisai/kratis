package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.ModelEntryDto;
import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ProviderType;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class ModelDiscoveryServiceTest {

    @Mock
    private ModelDiscoveryClient modelDiscoveryClient;

    private ModelDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new ModelDiscoveryService(modelDiscoveryClient);
    }

    @Test
    void discoverModels_openAi_shouldReturnModelIds() {
        String response = """
                {"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"},{"id":"gpt-3.5-turbo"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OPENAI, "test-key", null);

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo");
        assertThat(models).extracting(ModelEntryDto::baseModel).containsOnlyNulls();
    }

    @Test
    void discoverModels_openAiWithBaseUrl_shouldUseCustomUrl() {
        String response = """
                {"data":[{"id":"custom-model"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models =
                service.discoverModels(ProviderType.OPENAI, "test-key", "https://custom.api.com/v1");

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("custom-model");
    }

    @Test
    void discoverModels_openAiNoDataField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OPENAI, "test-key", null);

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_openAiRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any(), any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.OPENAI, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover OpenAI models");
    }

    @Test
    void discoverModels_openAiInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.OPENAI, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse OpenAI models response");
    }

    @Test
    void discoverModels_groq_shouldReturnModelIds() {
        String response = """
                {"data":[{"id":"llama-3.1-70b"},{"id":"mixtral-8x7b"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GROQ, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("llama-3.1-70b", "mixtral-8x7b");
    }

    @Test
    void discoverModels_groqNoDataField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GROQ, "test-key", null);

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_groqRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any(), any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.GROQ, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover Groq models");
    }

    @Test
    void discoverModels_groqInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.GROQ, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Groq models response");
    }

    @Test
    void discoverModels_anthropic_shouldReturnModelIds() {
        String response = """
                {"data":[{"id":"claude-3-5-sonnet-20241022"},{"id":"claude-3-haiku-20240307"}]}
                """;
        when(modelDiscoveryClient.getModelsWithXApiKey(any(), any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.ANTHROPIC, "test-key", null);

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("claude-3-5-sonnet-20241022", "claude-3-haiku-20240307");
    }

    @Test
    void discoverModels_anthropicWithBaseUrl_shouldHitCustomUrlWithHeaders() {
        String response = """
                {"data":[{"id":"custom-claude"}]}
                """;
        when(modelDiscoveryClient.getModelsWithXApiKey(any(), any(), any())).thenReturn(response);

        List<ModelEntryDto> models =
                service.discoverModels(ProviderType.ANTHROPIC, "test-key", "https://custom.anthropic.com");

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("custom-claude");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(modelDiscoveryClient).getModelsWithXApiKey(uriCaptor.capture(), eq("test-key"), eq("2023-06-01"));
        assertThat(uriCaptor.getValue().toString()).isEqualTo("https://custom.anthropic.com/v1/models");
    }

    @Test
    void discoverModels_anthropicNoDataField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModelsWithXApiKey(any(), any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.ANTHROPIC, "test-key", null);

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_anthropicRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModelsWithXApiKey(any(), any(), any()))
                .thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.ANTHROPIC, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover Anthropic models");
    }

    @Test
    void discoverModels_anthropicInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModelsWithXApiKey(any(), any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.ANTHROPIC, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Anthropic models response");
    }

    @Test
    void discoverModels_ollama_shouldReturnModelNames() {
        String response = """
                {"models":[{"name":"llama2"},{"name":"codellama"}]}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OLLAMA, null, null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("llama2", "codellama");
    }

    @Test
    void discoverModels_ollamaNoModelsField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OLLAMA, null, null);

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_ollamaRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.OLLAMA, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover Ollama models");
    }

    @Test
    void discoverModels_ollamaInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.OLLAMA, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Ollama models response");
    }

    @Test
    void discoverModels_ollama_shouldClassifyEmbeddingModels() {
        String response = """
                {"models":[{"name":"llama3"},{"name":"nomic-embed-text"}]}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OLLAMA, null, null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("llama3", "nomic-embed-text");
        assertThat(models).extracting(ModelEntryDto::kind).containsExactly(ModelKind.CHAT, ModelKind.EMBEDDING);
    }

    @Test
    void discoverModels_mistral_shouldReturnModelIds() {
        String response = """
                {"data":[{"id":"mistral-large"},{"id":"mistral-small"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.MISTRAL, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("mistral-large", "mistral-small");
    }

    @Test
    void discoverModels_mistralRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any(), any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.MISTRAL, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover Mistral models");
    }

    @Test
    void discoverModels_mistralInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.MISTRAL, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Mistral models response");
    }

    @Test
    void discoverModels_deepseek_shouldReturnModelIds() {
        String response = """
                {"data":[{"id":"deepseek-chat"},{"id":"deepseek-coder"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.DEEPSEEK, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("deepseek-chat", "deepseek-coder");
    }

    @Test
    void discoverModels_deepseekRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any(), any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.DEEPSEEK, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover DeepSeek models");
    }

    @Test
    void discoverModels_deepseekInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.DEEPSEEK, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse DeepSeek models response");
    }

    @Test
    void discoverModels_google_shouldReturnModelIdsWithoutPrefix() {
        String response = """
                {"models":[{"name":"models/gemini-2.0-flash"},{"name":"models/gemini-pro"}]}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GOOGLE, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("gemini-2.0-flash", "gemini-pro");
    }

    @Test
    void discoverModels_googleNoModelsField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GOOGLE, "test-key", null);

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_googleModelWithoutPrefix_shouldKeepName() {
        String response = """
                {"models":[{"name":"gemini-pro"}]}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GOOGLE, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("gemini-pro");
    }

    @Test
    void discoverModels_googleRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.GOOGLE, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover Google GenAI models");
    }

    @Test
    void discoverModels_googleInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.GOOGLE, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Google GenAI models response");
    }

    @Test
    void discoverModels_azureOpenAi_shouldReturnDeploymentsWithBaseModels() {
        String response = """
                {"data":[{"id":"gpt-4o-deployment","model":"gpt-4o"},{"id":"text-embedding-3-small-deployment","model":"text-embedding-3-small"}]}
                """;
        when(modelDiscoveryClient.getModelsWithApiKeyHeader(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(
                ProviderType.AZURE_OPENAI, "test-key", "https://my-resource.openai.azure.com/openai");

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("gpt-4o-deployment", "text-embedding-3-small-deployment");
        assertThat(models).extracting(ModelEntryDto::baseModel).containsExactly("gpt-4o", "text-embedding-3-small");
        assertThat(models).extracting(ModelEntryDto::kind).containsExactly(ModelKind.CHAT, ModelKind.EMBEDDING);
    }

    @Test
    void discoverModels_azureOpenAiWithBaseUrl_shouldHitDeploymentsUrl() {
        String response = """
                {"data":[{"id":"deployment-1","model":"gpt-4o"}]}
                """;
        when(modelDiscoveryClient.getModelsWithApiKeyHeader(any(), any())).thenReturn(response);

        service.discoverModels(ProviderType.AZURE_OPENAI, "test-key", "https://my-resource.openai.azure.com/openai");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(modelDiscoveryClient).getModelsWithApiKeyHeader(uriCaptor.capture(), eq("test-key"));
        assertThat(uriCaptor.getValue().toString())
                .isEqualTo(
                        "https://my-resource.openai.azure.com/openai/openai/deployments?api-version=2023-03-15-preview");
    }

    @Test
    void discoverModels_azureOpenAiMissingBaseUrl_shouldThrow() {
        assertThatThrownBy(() -> service.discoverModels(ProviderType.AZURE_OPENAI, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Azure OpenAI base URL is required");
    }

    @Test
    void discoverModels_azureOpenAiNoDataField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModelsWithApiKeyHeader(any(), any())).thenReturn(response);

        List<ModelEntryDto> models =
                service.discoverModels(ProviderType.AZURE_OPENAI, "test-key", "https://my-resource.openai.azure.com");

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_azureOpenAiRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModelsWithApiKeyHeader(any(), any()))
                .thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(
                        ProviderType.AZURE_OPENAI, "test-key", "https://my-resource.openai.azure.com/openai"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover Azure OpenAI models");
    }

    @Test
    void discoverModels_azureOpenAiInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModelsWithApiKeyHeader(any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(
                        ProviderType.AZURE_OPENAI, "test-key", "https://my-resource.openai.azure.com/openai"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Azure OpenAI models response");
    }

    @Test
    void discoverModels_bedrock_shouldUseOpenAiCompatibleEndpoint() {
        String response = """
                {"data":[{"id":"us.anthropic.claude-sonnet-4-6"},{"id":"amazon.titan-embed-text-v2:0"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(
                ProviderType.BEDROCK, "test-key", "https://bedrock-runtime.us-east-1.amazonaws.com");

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("us.anthropic.claude-sonnet-4-6", "amazon.titan-embed-text-v2:0");
        assertThat(models).extracting(ModelEntryDto::kind).containsExactly(ModelKind.CHAT, ModelKind.EMBEDDING);
        verify(modelDiscoveryClient).getModels(any(URI.class), eq("Bearer test-key"));
    }

    @Test
    void discoverModels_bedrockWithoutBaseUrl_shouldThrow() {
        assertThatThrownBy(() -> service.discoverModels(ProviderType.BEDROCK, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Base URL is required");
    }

    @Test
    void discoverModels_other_shouldReturnModelIds() {
        String response = """
                {"data":[{"id":"custom-model"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models =
                service.discoverModels(ProviderType.OTHER, "test-key", "https://custom.api.com/v1");

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("custom-model");
    }

    @Test
    void discoverModels_otherMissingBaseUrl_shouldThrow() {
        assertThatThrownBy(() -> service.discoverModels(ProviderType.OTHER, "test-key", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Base URL is required");
    }

    @Test
    void discoverModels_otherNoDataField_shouldReturnEmptyList() {
        String response = """
                {"other":"field"}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models =
                service.discoverModels(ProviderType.OTHER, "test-key", "https://custom.api.com/v1");

        assertThat(models).isEmpty();
    }

    @Test
    void discoverModels_otherRestClientException_shouldThrowRuntimeException() {
        when(modelDiscoveryClient.getModels(any(), any())).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> service.discoverModels(ProviderType.OTHER, "test-key", "https://custom.api.com/v1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to discover models");
    }

    @Test
    void discoverModels_otherInvalidJson_shouldThrowParseError() {
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.discoverModels(ProviderType.OTHER, "test-key", "https://custom.api.com/v1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse models response");
    }

    @Test
    void discoverModels_google_shouldExtractInputTokenLimitAsContextWindow() {
        String response = """
                {"models":[{"name":"models/gemini-2.0-flash","inputTokenLimit":1048576,"outputTokenLimit":8192}]}
                """;
        when(modelDiscoveryClient.getModels(any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GOOGLE, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsExactly(1048576L);
    }

    @Test
    void discoverModels_groq_shouldExtractContextWindow() {
        String response = """
                {"data":[{"id":"llama-3.1-70b","context_window":131072}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.GROQ, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsExactly(131072L);
    }

    @Test
    void discoverModels_mistral_shouldExtractMaxContextLength() {
        String response = """
                {"data":[{"id":"mistral-large","max_context_length":128000}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.MISTRAL, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsExactly(128000L);
    }

    @Test
    void discoverModels_openAiCompatible_shouldExtractMaxModelLen() {
        String response = """
                {"data":[{"id":"local-llama","max_model_len":32768}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OTHER, "test-key", "https://vllm.internal/v1");

        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsExactly(32768L);
    }

    @Test
    void discoverModels_openAiCompatible_shouldExtractLiteLlmMaxInputTokens() {
        String response = """
                {"data":[{"id":"routed-model","max_input_tokens":"200000"}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models =
                service.discoverModels(ProviderType.OTHER, "test-key", "https://gateway.internal/v1");

        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsExactly(200000L);
    }

    @Test
    void discoverModels_withoutContextWindowField_shouldLeaveContextWindowNull() {
        String response = """
                {"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini","context_window":0}]}
                """;
        when(modelDiscoveryClient.getModels(any(), any())).thenReturn(response);

        List<ModelEntryDto> models = service.discoverModels(ProviderType.OPENAI, "test-key", null);

        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsOnlyNulls();
    }

    @Test
    void looksLikeEmbeddingModel_shouldDetectEmbeddingPatterns() {
        assertThat(service.looksLikeEmbeddingModel("text-embedding-3-small")).isTrue();
        assertThat(service.looksLikeEmbeddingModel("bge-m3")).isTrue();
        assertThat(service.looksLikeEmbeddingModel("amazon.titan-embed-text-v2:0"))
                .isTrue();
        assertThat(service.looksLikeEmbeddingModel("gpt-4o")).isFalse();
        assertThat(service.looksLikeEmbeddingModel(null)).isFalse();
    }
}
