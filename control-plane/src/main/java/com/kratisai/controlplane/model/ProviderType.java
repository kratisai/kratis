package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;

public enum ProviderType {
    ANTHROPIC(ApiType.ANTHROPIC_MESSAGES, "anthropic", PricingSource.LITELLM),
    AZURE_OPENAI(ApiType.OPENAI_COMPATIBLE, "azure", PricingSource.LITELLM),
    BEDROCK(ApiType.OPENAI_COMPATIBLE, "openai", PricingSource.LITELLM),
    DEEPSEEK(ApiType.OPENAI_COMPATIBLE, "deepseek", PricingSource.LITELLM),
    GOOGLE(ApiType.GEMINI, "gemini", PricingSource.LITELLM),
    GROQ(ApiType.OPENAI_COMPATIBLE, "groq", PricingSource.LITELLM),
    // The Kilo gateway lists per-token rates on /models; LiteLLM's map does not know its IDs.
    KILO(ApiType.OPENAI_COMPATIBLE, "openai", PricingSource.PROVIDER_API),
    MISTRAL(ApiType.OPENAI_COMPATIBLE, "mistral", PricingSource.LITELLM),
    OLLAMA(ApiType.OPENAI_COMPATIBLE, "ollama", PricingSource.LITELLM),
    OPENAI(ApiType.OPENAI_COMPATIBLE, "openai", PricingSource.LITELLM),
    OTHER(ApiType.OPENAI_COMPATIBLE, "openai", PricingSource.LITELLM);

    private final ApiType apiType;

    // LiteLLM's custom_llm_provider routing label.
    private final String liteLlmType;

    private final PricingSource pricingSource;

    ProviderType(ApiType apiType, String liteLlmType, PricingSource pricingSource) {
        this.apiType = Objects.requireNonNull(apiType);
        this.liteLlmType = Objects.requireNonNull(liteLlmType);
        this.pricingSource = Objects.requireNonNull(pricingSource);
    }

    public ApiType getApiType() {
        return apiType;
    }

    public String getLiteLlmType() {
        return liteLlmType;
    }

    public PricingSource getPricingSource() {
        return pricingSource;
    }

    @JsonCreator
    public static ProviderType fromValue(String value) {
        for (ProviderType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown provider type: " + value);
    }
}
