package com.kratisai.controlplane.agentloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.util.json.JsonParser;
import tools.jackson.core.type.TypeReference;

class KratisToolTest {

    @Test
    void providerDiscoversKratisToolAndAppliesConverter() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new SampleTools())
                .build()
                .getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        ToolCallback callback = callbacks[0];
        assertThat(callback.getToolDefinition().name()).isEqualTo("sample");
        assertThat(callback.getToolDefinition().description()).isEqualTo("Sample tool");

        String schema = "{\"$ref\":\"#/$defs/Annotations\"}";
        String result = callback.call(JsonParser.toJson(Map.of("value", schema)));

        assertThat(JsonParser.fromJson(result, new TypeReference<Object>() {}))
                .isEqualTo(schema)
                .isInstanceOf(String.class);
    }

    static class SampleTools {

        @KratisTool(name = "sample", description = "Sample tool")
        public String sample(String value) {
            return value;
        }
    }
}
