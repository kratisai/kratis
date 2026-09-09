package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProviderType {
    ANTHROPIC("ANTHROPIC"),
    AZURE_OPENAI("AZURE_OPENAI"),
    BEDROCK("BEDROCK"),
    DEEPSEEK("DEEPSEEK"),
    GOOGLE("GOOGLE"),
    GROQ("GROQ"),
    MISTRAL("MISTRAL"),
    OLLAMA("OLLAMA"),
    OPENAI("OPENAI"),
    // Open-ai compatible providers
    OTHER("OTHER");

    private final String value;

    ProviderType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ProviderType fromValue(String value) {
        for (ProviderType type : ProviderType.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown provider type: " + value);
    }
}
