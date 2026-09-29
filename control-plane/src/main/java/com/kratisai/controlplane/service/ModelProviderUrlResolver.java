package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import org.springframework.util.StringUtils;

public final class ModelProviderUrlResolver {

    public static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com";
    public static final String DEFAULT_GROQ_URL = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_MISTRAL_URL = "https://api.mistral.ai/v1";
    public static final String DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com";

    private ModelProviderUrlResolver() {}

    public static String resolveDirectBaseUrl(ModelProvider provider) {
        return switch (provider.getProviderType()) {
            case OPENAI -> getBaseUrl(provider, DEFAULT_OPENAI_URL);
            case ANTHROPIC -> getBaseUrl(provider, DEFAULT_ANTHROPIC_URL);
            case GROQ -> getBaseUrl(provider, DEFAULT_GROQ_URL);
            case MISTRAL -> getBaseUrl(provider, DEFAULT_MISTRAL_URL);
            case DEEPSEEK -> getBaseUrl(provider, DEFAULT_DEEPSEEK_URL);
            case AZURE_OPENAI -> requireBaseUrl(provider, "Azure OpenAI");
            case BEDROCK -> requireBaseUrl(provider, "AWS Bedrock");
            case OLLAMA -> requireBaseUrl(provider, "Ollama");
            case OTHER -> requireBaseUrl(provider, "Other (OpenAI-compatible)");
            case GOOGLE -> provider.getBaseUrl();
        };
    }

    public static String resolveLiteLLMBaseUrl(ProviderType providerType, String liteLLMBaseUrl) {
        return switch (providerType) {
            case OPENAI, AZURE_OPENAI, GROQ, OLLAMA, MISTRAL, DEEPSEEK, BEDROCK, OTHER -> liteLLMBaseUrl + "/v1";
            case ANTHROPIC, GOOGLE -> liteLLMBaseUrl;
        };
    }

    private static String getBaseUrl(ModelProvider provider, String defaultUrl) {
        return StringUtils.hasText(provider.getBaseUrl()) ? provider.getBaseUrl() : defaultUrl;
    }

    private static String requireBaseUrl(ModelProvider provider, String providerLabel) {
        if (!StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalArgumentException(providerLabel + " requires a base URL");
        }
        return provider.getBaseUrl();
    }
}
