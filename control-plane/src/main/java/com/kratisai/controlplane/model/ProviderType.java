package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;

public enum ProviderType {
    ANTHROPIC(ApiType.ANTHROPIC_MESSAGES, "anthropic"),
    AZURE_OPENAI(ApiType.OPENAI_COMPATIBLE, "azure"),
    BEDROCK(ApiType.OPENAI_COMPATIBLE, "openai"),
    DEEPSEEK(ApiType.OPENAI_COMPATIBLE, "deepseek"),
    GOOGLE(ApiType.GEMINI, "gemini"),
    GROQ(ApiType.OPENAI_COMPATIBLE, "groq"),
    KILO(ApiType.OPENAI_COMPATIBLE, "openai"),
    MISTRAL(ApiType.OPENAI_COMPATIBLE, "mistral"),
    OLLAMA(ApiType.OPENAI_COMPATIBLE, "ollama"),
    OPENAI(ApiType.OPENAI_COMPATIBLE, "openai"),
    OTHER(ApiType.OPENAI_COMPATIBLE, "openai");

    private final ApiType apiType;

    // LiteLLM's custom_llm_provider routing label.
    private final String liteLlmType;

    ProviderType(ApiType apiType, String liteLlmType) {
        this.apiType = Objects.requireNonNull(apiType);
        this.liteLlmType = Objects.requireNonNull(liteLlmType);
    }

    public ApiType getApiType() {
        return apiType;
    }

    public String getLiteLlmType() {
        return liteLlmType;
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
