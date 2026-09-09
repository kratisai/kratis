package com.kratisai.controlplane.client.litellm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.AddModelRequest;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.AddModelResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ChatChoice;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ChatCompletionRequest;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ChatCompletionResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ChatMessage;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.DeleteKeyRequest;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.DeleteKeyResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.DeleteModelRequest;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.DeleteModelResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.GenerateKeyRequest;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.GenerateKeyResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.KeyInfoData;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.KeyInfoResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ListModelsResponse;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ListModelsV2Response;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.LiteLLMParams;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ModelConfig;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ModelInfo;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.SpendLogEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiteLLMDtoTest {

    @Test
    void records_canBeConstructedAndAccessed() {
        LiteLLMParams params = new LiteLLMParams("gpt-4o", "key", "openai", "https://api.openai.com");
        AddModelRequest addModelRequest = new AddModelRequest("my-model", params);
        assertThat(addModelRequest.modelName()).isEqualTo("my-model");
        assertThat(addModelRequest.modelInfo()).isNull();

        AddModelRequest withInfo = new AddModelRequest("my-model", params, new ModelInfo("chat"));
        assertThat(withInfo.modelInfo().mode()).isEqualTo("chat");

        assertThat(new AddModelResponse(Map.of("key", "value")).data()).containsEntry("key", "value");
        assertThat(new ListModelsResponse(List.of(new ModelConfig("name", params))).data())
                .hasSize(1);
        assertThat(new ListModelsV2Response(List.of(new ModelConfig("name", params))).data())
                .hasSize(1);
        assertThat(new ModelConfig("name", params).modelName()).isEqualTo("name");

        assertThat(new DeleteModelRequest("id").id()).isEqualTo("id");
        assertThat(new DeleteModelResponse(true).deleted()).isTrue();

        ChatMessage message = new ChatMessage("user", "hello");
        assertThat(new ChatCompletionRequest("model", List.of(message), 100).model())
                .isEqualTo("model");
        assertThat(message.role()).isEqualTo("user");
        assertThat(message.content()).isEqualTo("hello");

        assertThat(new ChatCompletionResponse("id", List.of(new ChatChoice(message, "stop"))).id())
                .isEqualTo("id");
        assertThat(new ChatChoice(message, "stop").finishReason()).isEqualTo("stop");

        assertThat(new GenerateKeyRequest("alias", List.of("model")).keyAlias()).isEqualTo("alias");
        assertThat(new GenerateKeyResponse("key", "alias").key()).isEqualTo("key");
        assertThat(new DeleteKeyRequest(List.of("key"), List.of("alias")).keys())
                .containsExactly("key");
        assertThat(new DeleteKeyResponse(List.of("key"), "ok").message()).isEqualTo("ok");

        assertThat(new KeyInfoData("alias", 0.10).spend()).isEqualTo(0.10);
        assertThat(new KeyInfoResponse("key", new KeyInfoData("alias", 0.10))
                        .info()
                        .keyAlias())
                .isEqualTo("alias");
        SpendLogEntry entry = new SpendLogEntry(100L, 60L, 40L);
        assertThat(entry.totalTokens()).isEqualTo(100L);
        assertThat(entry.promptTokens()).isEqualTo(60L);
        assertThat(entry.completionTokens()).isEqualTo(40L);
    }

    @Test
    void spendLogEntry_mapsTokenFieldsFromLiteLLMRow() throws Exception {
        String row = """
                {"request_id":"chatcmpl-abc","call_type":"acompletion","api_key":"abc123hash",
                 "spend":0.01,"total_tokens":150,"prompt_tokens":90,"completion_tokens":60,
                 "model":"gpt-4o","user":"u1","startTime":"2026-08-23T00:00:00Z","status":"success"}""";
        ObjectMapper objectMapper = new ObjectMapper();

        List<SpendLogEntry> rows = objectMapper.readValue("[" + row + "]", new TypeReference<List<SpendLogEntry>>() {});

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().totalTokens()).isEqualTo(150L);
        assertThat(rows.getFirst().promptTokens()).isEqualTo(90L);
        assertThat(rows.getFirst().completionTokens()).isEqualTo(60L);
    }
}
