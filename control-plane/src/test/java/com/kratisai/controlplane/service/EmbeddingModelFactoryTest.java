package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

@SpringIntegrationTest
class EmbeddingModelFactoryTest {

    @Autowired
    @Qualifier("embeddingModelFactory") // The real EmbeddingModelFactory.
    private EmbeddingModelFactory embeddingModelFactory;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();
    }

    @Test
    void shouldCreateNativeOpenAiModel() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        EmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(provider, "gpt-4o");

        assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void shouldCreateNativeGoogleModel() {
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        // We simulate a team that mistakenly set a Chat model name (e.g.
        // gemini-1.5-pro)
        EmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(provider, "gemini-1.5-pro");

        assertThat(embeddingModel).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
    }

    @Test
    void shouldInterceptGoogleOnProxyAndReturnOpenAiCompatibleModel() {
        // Simulates a user configuring "Google" but pointing to a LiteLLM/OpenAI
        // compatible proxy
        // Base URL
        ModelProvider provider =
                new ModelProvider("LiteLLM Proxy (Google)", ProviderType.GOOGLE, "sk-fake", "http://localhost:4000/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        EmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(provider, "google/gemini-1.5-pro");

        // The factory should intercept the custom base URL and build an OpenAI
        // compatible client
        assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void shouldInterceptBedrockOnProxyAndReturnOpenAiCompatibleModel() {
        // Simulates Bedrock on a proxy
        ModelProvider provider = new ModelProvider(
                "LiteLLM Proxy (Bedrock)", ProviderType.BEDROCK, "sk-fake", "http://localhost:4000/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        EmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(provider, "amazon.titan-text");

        assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void shouldRouteOllamaToOpenAiCompatibleModel() {
        // Ollama native integration
        ModelProvider provider =
                new ModelProvider("Local Ollama", ProviderType.OLLAMA, "ollama", "http://localhost:11434/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        EmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(provider, "llama3");

        assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void shouldThrowExceptionWhenProviderIsNull() {
        assertThatThrownBy(() -> embeddingModelFactory.createEmbeddingModel(null, "gpt-4o"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModelProvider cannot be null");
    }

    @Test
    void shouldThrowExceptionForAzureOpenAiWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Azure Account", ProviderType.AZURE_OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> embeddingModelFactory.createEmbeddingModel(provider, "text-embedding-3-small"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Azure OpenAI requires a base URL");
    }

    @Test
    void shouldThrowExceptionForBedrockWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Bedrock Account", ProviderType.BEDROCK, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> embeddingModelFactory.createEmbeddingModel(provider, "amazon.titan-embed-text-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWS Bedrock requires a base URL");
    }

    @Test
    void shouldThrowExceptionForOllamaWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Ollama Account", ProviderType.OLLAMA, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> embeddingModelFactory.createEmbeddingModel(provider, "nomic-embed-text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ollama requires a base URL");
    }

    @Test
    void shouldThrowExceptionForOtherWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Other Account", ProviderType.OTHER, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> embeddingModelFactory.createEmbeddingModel(provider, "custom-embedding"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Other (OpenAI-compatible) requires a base URL");
    }

    @Test
    void shouldRouteGoogleProviderThroughLiteLLMToOpenAiCompatibleModel() {
        // When using LiteLLM, even Google providers must be routed through the
        // OpenAI-compatible endpoint, not the native Google GenAI client
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        EmbeddingModel embeddingModel =
                embeddingModelFactory.createEmbeddingModelViaLiteLLM(provider, "gemini-embedding-001", "vk-fake");

        assertThat(embeddingModel).isInstanceOf(OpenAiEmbeddingModel.class);
    }
}
