package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.restdto.ModelEntryDto;
import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ProviderType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Exercises {@link ModelDiscoveryService} against a mock HTTP server. The client is built over an
 * isolated {@link RestTemplate} so the mock server never intercepts the shared application bean.
 */
@SpringIntegrationTest
class ModelDiscoveryIntegrationTest {

    private ModelDiscoveryService modelDiscoveryService;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate isolatedRestTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(isolatedRestTemplate).build();
        RestClient restClient = RestClient.builder()
                .requestFactory((uri, httpMethod) ->
                        isolatedRestTemplate.getRequestFactory().createRequest(uri, httpMethod))
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        ModelDiscoveryClient client =
                HttpServiceProxyFactory.builderFor(adapter).build().createClient(ModelDiscoveryClient.class);
        modelDiscoveryService = new ModelDiscoveryService(client);
    }

    @Test
    void shouldDiscoverOpenAiModels() {
        String responseBody = "{\"data\": [{\"id\": \"gpt-4o\"}, {\"id\": \"gpt-3.5-turbo\"}]}";
        server.expect(requestTo("https://api.openai.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.OPENAI, "dummy-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("gpt-4o", "gpt-3.5-turbo");
        server.verify();
    }

    @Test
    void shouldDiscoverGroqModels() {
        String responseBody = "{\"data\": [{\"id\": \"llama3-70b-8192\"}]}";
        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.GROQ, "dummy-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("llama3-70b-8192");
        server.verify();
    }

    @Test
    void shouldDiscoverAnthropicModels() {
        String responseBody =
                "{\"data\": [{\"id\": \"claude-3-5-sonnet-20241022\"}, {\"id\": \"claude-3-haiku-20240307\"}]}";
        server.expect(requestTo("https://api.anthropic.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.ANTHROPIC, "dummy-key", null);

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("claude-3-5-sonnet-20241022", "claude-3-haiku-20240307");
        server.verify();
    }

    @Test
    void shouldDiscoverOllamaModels() {
        String responseBody = "{\"models\": [{\"name\": \"llama3:latest\"}]}";
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.OLLAMA, null, null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("llama3:latest");
        server.verify();
    }

    @Test
    void shouldDiscoverMistralModels() {
        String responseBody = "{\"data\": [{\"id\": \"mistral-large-latest\"}]}";
        server.expect(requestTo("https://api.mistral.ai/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.MISTRAL, "dummy-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("mistral-large-latest");
        server.verify();
    }

    @Test
    void shouldDiscoverDeepSeekModels() {
        String responseBody = "{\"data\": [{\"id\": \"deepseek-chat\"}]}";
        server.expect(requestTo("https://api.deepseek.com/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.DEEPSEEK, "dummy-key", null);

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("deepseek-chat");
        server.verify();
    }

    @Test
    void shouldDiscoverGoogleGenAiModels() {
        String responseBody =
                "{\"models\": [{\"name\": \"models/gemini-2.0-flash\", \"inputTokenLimit\": 1048576}, {\"name\": \"models/gemini-2.0-flash-lite\"}, {\"name\": \"models/gemini-1.5-pro\"}]}";
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models?key=dummy-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(ProviderType.GOOGLE, "dummy-key", null);

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-pro");
        assertThat(models).extracting(ModelEntryDto::contextWindowTokens).containsExactly(1048576L, null, null);
        server.verify();
    }

    @Test
    void shouldDiscoverAzureOpenAiModels() {
        String responseBody = "{\"data\": [{\"id\": \"gpt-4o-deployment\", \"model\": \"gpt-4o\"}, "
                + "{\"id\": \"embedding-deployment\", \"model\": \"text-embedding-3-small\"}]}";
        server.expect(
                        requestTo(
                                "https://my-resource.openai.azure.com/openai/openai/deployments?api-version=2023-03-15-preview"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(
                ProviderType.AZURE_OPENAI, "dummy-key", "https://my-resource.openai.azure.com/openai");

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("gpt-4o-deployment", "embedding-deployment");
        assertThat(models).extracting(ModelEntryDto::baseModel).containsExactly("gpt-4o", "text-embedding-3-small");
        server.verify();
    }

    @Test
    void shouldDiscoverOtherModels() {
        String responseBody = "{\"data\": [{\"id\": \"custom-model\"}]}";
        server.expect(requestTo("https://custom.api.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models =
                modelDiscoveryService.discoverModels(ProviderType.OTHER, "dummy-key", "https://custom.api.com/v1");

        assertThat(models).extracting(ModelEntryDto::modelName).containsExactly("custom-model");
        server.verify();
    }

    @Test
    void shouldDiscoverBedrockModelsViaOpenAiCompatibleEndpoint() {
        String responseBody =
                "{\"data\": [{\"id\": \"us.anthropic.claude-sonnet-4-6\"}, {\"id\": \"amazon.titan-embed-text-v2:0\"}]}";
        server.expect(requestTo("https://bedrock-runtime.us-east-1.amazonaws.com/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<ModelEntryDto> models = modelDiscoveryService.discoverModels(
                ProviderType.BEDROCK, "test-key", "https://bedrock-runtime.us-east-1.amazonaws.com");

        assertThat(models)
                .extracting(ModelEntryDto::modelName)
                .containsExactly("us.anthropic.claude-sonnet-4-6", "amazon.titan-embed-text-v2:0");
        assertThat(models).extracting(ModelEntryDto::kind).containsExactly(ModelKind.CHAT, ModelKind.EMBEDDING);
        server.verify();
    }
}
