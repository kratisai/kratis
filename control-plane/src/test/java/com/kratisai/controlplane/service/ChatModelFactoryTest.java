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
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
class ChatModelFactoryTest {

    @Autowired
    @Qualifier("chatModelFactory") // The real ChatModelFactory (bypasses the @Primary test fake).
    private ChatModelFactory chatModelFactory;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private EntityManager entityManager;

    private Team team;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();
    }

    @Test
    void shouldCreateOpenAiModel() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "gpt-4o");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateGoogleModel() {
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "gemini-1.5-pro");

        assertThat(chatModel).isInstanceOf(GoogleGenAiChatModel.class);
    }

    @Test
    void shouldRouteOllamaToOpenAiCompatibleModel() {
        ModelProvider provider =
                new ModelProvider("Local Ollama", ProviderType.OLLAMA, "ollama", "http://localhost:11434/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "llama3");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldThrowExceptionWhenProviderIsNull() {
        assertThatThrownBy(() -> chatModelFactory.createChatModel(null, "gpt-4o"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModelProvider cannot be null");
    }

    @Test
    void shouldThrowExceptionWhenModelNameIsBlank() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModel(provider, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelNameOverride is required");
    }

    @Test
    void shouldCreateAzureOpenAiModelWhenBaseUrlIsProvided() {
        ModelProvider provider = new ModelProvider(
                "Azure Account", ProviderType.AZURE_OPENAI, "sk-fake", "https://my-resource.openai.azure.com/openai");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "gpt-4o-deployment");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldThrowExceptionForAzureOpenAiWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Azure Account", ProviderType.AZURE_OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModel(provider, "gpt-4o-deployment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Azure OpenAI requires a base URL");
    }

    @Test
    void shouldThrowExceptionForBedrockWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Bedrock Account", ProviderType.BEDROCK, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModel(provider, "anthropic.claude-v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWS Bedrock requires a base URL");
    }

    @Test
    void shouldThrowExceptionForOllamaWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Ollama Account", ProviderType.OLLAMA, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModel(provider, "llama3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ollama requires a base URL");
    }

    @Test
    void shouldThrowExceptionForOtherWithoutBaseUrl() {
        ModelProvider provider = new ModelProvider("Other Account", ProviderType.OTHER, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModel(provider, "custom-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Other (OpenAI-compatible) requires a base URL");
    }

    /**
     * Regression test for an intermittent NullPointerException seen in production ingestion runs:
     * {@code Cannot invoke "ProviderType.ordinal()" because the return value of
     * "ModelProvider.getProviderType()" is null}.
     *
     * <p>The bug was caused by callers (see {@code DimensionResearchService#researchDimensions})
     * sharing a single, uninitialized Hibernate lazy proxy for {@link ModelProvider} across many
     * concurrently-submitted virtual threads. Hibernate's proxy initialization is not thread-safe:
     * when several threads raced to call {@code createChatModel} (and thus {@code
     * provider.getProviderType()}) on the very first access of that shared proxy, one thread could
     * observe the target entity mid-hydration - with {@code providerType} still at its Java default
     * of {@code null} - triggering the NPE inside the enum {@code switch}.
     *
     * <p>The fix is to force-initialize the proxy once, on the thread that owns the active Hibernate
     * Session, before it is ever handed to worker threads. This test reproduces a genuine
     * uninitialized proxy (via {@link ModelProviderRepository#getReferenceById}) and verifies that,
     * once initialized (mirroring the fix), it can be safely and correctly read concurrently by many
     * virtual threads calling {@code ChatModelFactory.createChatModel} without ever observing a
     * partially-hydrated entity.
     */
    @Test
    @Transactional
    void shouldSafelyCreateChatModelsConcurrentlyFromInitializedSharedProxy() throws Exception {
        ModelProvider realProvider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        realProvider.setTeam(team);
        realProvider = modelProviderRepository.saveAndFlush(realProvider);
        var providerId = realProvider.getId();

        // Detach the just-saved entity from this session's persistence context. Without this,
        // getReferenceById below would simply return the same already-managed, fully-hydrated
        // instance rather than a fresh proxy (Hibernate returns the existing managed entity from
        // the persistence context instead of creating a new proxy for it).
        entityManager.flush();
        entityManager.clear();

        // getReferenceById returns an uninitialized Hibernate lazy proxy bound to this
        // transaction/session, reproducing the exact shape of team.getIngestionProvider()
        // before the DimensionResearchService fix.
        ModelProvider proxy = modelProviderRepository.getReferenceById(providerId);
        assertThat(Hibernate.isInitialized(proxy)).isFalse();

        // Mirrors the fix: force-initialize on the owning thread before fanning out.
        Hibernate.initialize(proxy);
        assertThat(Hibernate.isInitialized(proxy)).isTrue();

        int concurrency = 20;
        List<Callable<ChatModel>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> chatModelFactory.createChatModel(proxy, "gemini-1.5-pro"));
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChatModel>> futures = executor.invokeAll(tasks);
            for (Future<ChatModel> future : futures) {
                // future.get() re-throws any exception (e.g. the original NPE) from the worker thread.
                assertThat(future.get()).isInstanceOf(GoogleGenAiChatModel.class);
            }
        }
    }

    @Test
    void shouldCreateOpenAiModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModelViaLiteLLM(
                provider, "openai-provider-gpt-4o-abc123", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateGoogleModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModelViaLiteLLM(
                provider, "google-provider-gemini-1.5-pro-abc123", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(GoogleGenAiChatModel.class);
    }

    @Test
    void shouldRouteOllamaToOpenAiCompatibleModelViaLiteLLM() {
        ModelProvider provider =
                new ModelProvider("Local Ollama", ProviderType.OLLAMA, "ollama", "http://localhost:11434/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModelViaLiteLLM(
                provider, "ollama-provider-llama3-abc123", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldThrowExceptionWhenProviderIsNullForLiteLLM() {
        assertThatThrownBy(
                        () -> chatModelFactory.createChatModelViaLiteLLM(null, "model-name", "sk-virtual-key", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModelProvider cannot be null");
    }

    @Test
    void shouldThrowExceptionWhenLiteLLMModelNameIsBlank() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModelViaLiteLLM(provider, " ", "sk-virtual-key", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("litellmModelName is required");
    }

    @Test
    void shouldCreateGroqModel() {
        ModelProvider provider = new ModelProvider("Groq", ProviderType.GROQ, "gsk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "llama3-70b");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateAnthropicModel() {
        ModelProvider provider = new ModelProvider("Anthropic", ProviderType.ANTHROPIC, "sk-ant-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "claude-3-opus");

        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void shouldCreateMistralModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("Mistral", ProviderType.MISTRAL, "api-key", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(provider, "mistral-model", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateAnthropicModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("Anthropic", ProviderType.ANTHROPIC, "api-key", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(provider, "claude-model", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void shouldCreateOpenAiModelWithThinkingEnabled() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "o3-mini", true);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateAnthropicModelWithThinkingEnabled() {
        ModelProvider provider = new ModelProvider("Anthropic", ProviderType.ANTHROPIC, "sk-ant-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "claude-3-7-sonnet", true);

        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void shouldCreateGoogleModelWithThinkingEnabled() {
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "gemini-2.5-pro", true);

        assertThat(chatModel).isInstanceOf(GoogleGenAiChatModel.class);
    }

    @Test
    void shouldUseDefaultBaseUrlWhenNotProvided() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "gpt-4o");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldUseProvidedBaseUrlForOpenAi() {
        ModelProvider provider =
                new ModelProvider("OpenAI Compatible", ProviderType.OPENAI, "sk-fake", "https://custom.openai.com/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "custom-model");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateOpenAiModelWithOneArgOverload() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "gpt-4o");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldThrowExceptionForGoogleWithoutApiKey() {
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, null, null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModel(provider, "gemini-1.5-pro"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldCreateModelForUnknownProviderAsOpenAiCompatible() {
        ModelProvider provider = new ModelProvider("Other", ProviderType.OTHER, "sk-fake", "https://other.com/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModel(provider, "other-model");

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateOllamaModelViaLiteLLM() {
        ModelProvider provider =
                new ModelProvider("Local Ollama", ProviderType.OLLAMA, "ollama", "http://localhost:11434/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModelViaLiteLLM(
                provider, "ollama-provider-llama3-abc123", "sk-virtual-key", true);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateDeepseekModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("DeepSeek", ProviderType.DEEPSEEK, "api-key", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(provider, "deepseek-model", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateBedrockModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("Bedrock", ProviderType.BEDROCK, "api-key", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(provider, "bedrock-model", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateOtherProviderModelViaLiteLLM() {
        ModelProvider provider = new ModelProvider("Other", ProviderType.OTHER, "api-key", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(provider, "other-model", "sk-virtual-key", false);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldThrowExceptionWhenVirtualKeyIsNull() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        assertThatThrownBy(() -> chatModelFactory.createChatModelViaLiteLLM(provider, "model-name", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("virtualKey is required");
    }

    @Test
    void shouldCreateOpenAiModelViaLiteLLMWithThinking() {
        ModelProvider provider = new ModelProvider("OpenAI Account", ProviderType.OPENAI, "sk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModelViaLiteLLM(
                provider, "openai-provider-gpt-4o-abc123", "sk-virtual-key", true);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldCreateGoogleModelViaLiteLLMWithThinking() {
        ModelProvider provider = new ModelProvider("Google Cloud", ProviderType.GOOGLE, "AIzaSyFake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel = chatModelFactory.createChatModelViaLiteLLM(
                provider, "google-provider-gemini-1.5-pro-abc123", "sk-virtual-key", true);

        assertThat(chatModel).isInstanceOf(GoogleGenAiChatModel.class);
    }

    @Test
    void shouldCreateGroqModelViaLiteLLMWithThinking() {
        ModelProvider provider = new ModelProvider("Groq", ProviderType.GROQ, "gsk-fake", null);
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        ChatModel chatModel =
                chatModelFactory.createChatModelViaLiteLLM(provider, "groq-model", "sk-virtual-key", true);

        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }
}
