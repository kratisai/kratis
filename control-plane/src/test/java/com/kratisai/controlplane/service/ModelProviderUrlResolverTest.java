package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import org.junit.jupiter.api.Test;

class ModelProviderUrlResolverTest {

    @Test
    void shouldResolveDirectDefaultUrls() {
        ModelProvider openai = new ModelProvider("OpenAI", ProviderType.OPENAI, "key", null);
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(openai))
                .isEqualTo(ModelProviderUrlResolver.DEFAULT_OPENAI_URL);

        ModelProvider anthropic = new ModelProvider("Anthropic", ProviderType.ANTHROPIC, "key", null);
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(anthropic))
                .isEqualTo(ModelProviderUrlResolver.DEFAULT_ANTHROPIC_URL);

        ModelProvider groq = new ModelProvider("Groq", ProviderType.GROQ, "key", null);
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(groq))
                .isEqualTo(ModelProviderUrlResolver.DEFAULT_GROQ_URL);

        ModelProvider mistral = new ModelProvider("Mistral", ProviderType.MISTRAL, "key", null);
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(mistral))
                .isEqualTo(ModelProviderUrlResolver.DEFAULT_MISTRAL_URL);

        ModelProvider deepseek = new ModelProvider("DeepSeek", ProviderType.DEEPSEEK, "key", null);
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(deepseek))
                .isEqualTo(ModelProviderUrlResolver.DEFAULT_DEEPSEEK_URL);
    }

    @Test
    void shouldHonorCustomBaseUrlWhenSpecified() {
        ModelProvider openai = new ModelProvider("OpenAI", ProviderType.OPENAI, "key", "https://proxy.example.com/v1");
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(openai)).isEqualTo("https://proxy.example.com/v1");

        ModelProvider google = new ModelProvider("Google", ProviderType.GOOGLE, "key", "https://custom.gemini.com");
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(google)).isEqualTo("https://custom.gemini.com");
    }

    @Test
    void shouldReturnNullForGoogleWithoutBaseUrl() {
        ModelProvider google = new ModelProvider("Google", ProviderType.GOOGLE, "key", null);
        assertThat(ModelProviderUrlResolver.resolveDirectBaseUrl(google)).isNull();
    }

    @Test
    void shouldThrowWhenBaseUrlMissingForRequiredProviders() {
        ModelProvider azure = new ModelProvider("Azure", ProviderType.AZURE_OPENAI, "key", null);
        assertThatThrownBy(() -> ModelProviderUrlResolver.resolveDirectBaseUrl(azure))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Azure OpenAI requires a base URL");

        ModelProvider bedrock = new ModelProvider("Bedrock", ProviderType.BEDROCK, "key", null);
        assertThatThrownBy(() -> ModelProviderUrlResolver.resolveDirectBaseUrl(bedrock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWS Bedrock requires a base URL");

        ModelProvider ollama = new ModelProvider("Ollama", ProviderType.OLLAMA, "key", null);
        assertThatThrownBy(() -> ModelProviderUrlResolver.resolveDirectBaseUrl(ollama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ollama requires a base URL");

        ModelProvider other = new ModelProvider("Other", ProviderType.OTHER, "key", null);
        assertThatThrownBy(() -> ModelProviderUrlResolver.resolveDirectBaseUrl(other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Other (OpenAI-compatible) requires a base URL");
    }

    @Test
    void shouldResolveLiteLLMBaseUrls() {
        String liteLLM = "http://litellm:4000";
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.OPENAI, liteLLM))
                .isEqualTo("http://litellm:4000/v1");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.AZURE_OPENAI, liteLLM))
                .isEqualTo("http://litellm:4000/v1");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.BEDROCK, liteLLM))
                .isEqualTo("http://litellm:4000/v1");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.OLLAMA, liteLLM))
                .isEqualTo("http://litellm:4000/v1");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.MISTRAL, liteLLM))
                .isEqualTo("http://litellm:4000/v1");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.DEEPSEEK, liteLLM))
                .isEqualTo("http://litellm:4000/v1");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.OTHER, liteLLM))
                .isEqualTo("http://litellm:4000/v1");

        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.ANTHROPIC, liteLLM))
                .isEqualTo("http://litellm:4000");
        assertThat(ModelProviderUrlResolver.resolveLiteLLMBaseUrl(ProviderType.GOOGLE, liteLLM))
                .isEqualTo("http://litellm:4000");
    }
}
