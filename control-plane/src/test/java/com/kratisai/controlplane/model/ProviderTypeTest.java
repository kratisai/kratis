package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProviderTypeTest {

    @Test
    void liteLlmType_routesEveryAutoProvisionableType() {
        assertThat(ProviderType.ANTHROPIC.getLiteLlmType()).isEqualTo("anthropic");
        assertThat(ProviderType.AZURE_OPENAI.getLiteLlmType()).isEqualTo("azure");
        assertThat(ProviderType.BEDROCK.getLiteLlmType()).isEqualTo("openai");
        assertThat(ProviderType.DEEPSEEK.getLiteLlmType()).isEqualTo("deepseek");
        assertThat(ProviderType.GOOGLE.getLiteLlmType()).isEqualTo("gemini");
        assertThat(ProviderType.GROQ.getLiteLlmType()).isEqualTo("groq");
        assertThat(ProviderType.KILO.getLiteLlmType()).isEqualTo("openai");
        assertThat(ProviderType.MISTRAL.getLiteLlmType()).isEqualTo("mistral");
        assertThat(ProviderType.OLLAMA.getLiteLlmType()).isEqualTo("ollama");
        assertThat(ProviderType.OPENAI.getLiteLlmType()).isEqualTo("openai");
        assertThat(ProviderType.OTHER.getLiteLlmType()).isEqualTo("openai");
    }

    @Test
    void apiType_isDefinedForEveryValue() {
        assertThat(ProviderType.ANTHROPIC.getApiType()).isEqualTo(ApiType.ANTHROPIC_MESSAGES);
        assertThat(ProviderType.AZURE_OPENAI.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.BEDROCK.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.DEEPSEEK.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.GOOGLE.getApiType()).isEqualTo(ApiType.GEMINI);
        assertThat(ProviderType.GROQ.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.KILO.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.MISTRAL.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.OLLAMA.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.OPENAI.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);
        assertThat(ProviderType.OTHER.getApiType()).isEqualTo(ApiType.OPENAI_COMPATIBLE);

        Set<ApiType> used = Arrays.stream(ProviderType.values())
                .map(ProviderType::getApiType)
                .collect(Collectors.toSet());
        assertThat(used).containsExactlyInAnyOrder(ApiType.values());
    }

    @Test
    void jsonWireValues_unchangedForEveryValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (ProviderType type : ProviderType.values()) {
            String json = mapper.writeValueAsString(type);
            assertThat(json).isEqualTo("\"" + type.name() + "\"");
            assertThat(mapper.readValue(json, ProviderType.class)).isEqualTo(type);
        }
    }

    @Test
    void fromValue_rejectsUnknownValues() {
        assertThatThrownBy(() -> ProviderType.fromValue("NOPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown provider type: NOPE");
    }

    @Test
    void liteLlmType_isPresentForEveryValue() {
        for (ProviderType type : ProviderType.values()) {
            assertThat(type.getLiteLlmType()).isNotBlank();
        }
    }
}
