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
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ModelCostEntry;
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
        assertThat(entry.spend()).isNull();
        assertThat(entry.model()).isNull();
        assertThat(entry.modelGroup()).isNull();

        SpendLogEntry priced = new SpendLogEntry(200L, 150L, 50L, 0.02, "raw-model", "model-group");
        assertThat(priced.spend()).isEqualTo(0.02);
        assertThat(priced.model()).isEqualTo("raw-model");
        assertThat(priced.modelGroup()).isEqualTo("model-group");
    }

    @Test
    void spendLogEntry_mapsTokenFieldsFromLiteLLMRow() throws Exception {
        String row = """
                {"request_id":"chatcmpl-abc","call_type":"acompletion","api_key":"abc123hash",
                 "spend":0.01,"total_tokens":150,"prompt_tokens":90,"completion_tokens":60,
                 "model":"gemini-flash-latest","model_group":"google-google-gemini-flash-latest-838d9bd6",
                 "user":"u1","startTime":"2026-08-23T00:00:00Z","status":"success"}""";
        ObjectMapper objectMapper = new ObjectMapper();

        List<SpendLogEntry> rows = objectMapper.readValue("[" + row + "]", new TypeReference<List<SpendLogEntry>>() {});

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().totalTokens()).isEqualTo(150L);
        assertThat(rows.getFirst().promptTokens()).isEqualTo(90L);
        assertThat(rows.getFirst().completionTokens()).isEqualTo(60L);
        assertThat(rows.getFirst().spend()).isEqualTo(0.01);
        assertThat(rows.getFirst().model()).isEqualTo("gemini-flash-latest");
        assertThat(rows.getFirst().modelGroup()).isEqualTo("google-google-gemini-flash-latest-838d9bd6");
    }

    @Test
    void spendLogEntry_toleratesLegacyRowsWithoutModelFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        List<SpendLogEntry> rows = objectMapper.readValue(
                "[{\"total_tokens\":10,\"prompt_tokens\":8,\"completion_tokens\":2}]",
                new TypeReference<List<SpendLogEntry>>() {});

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().totalTokens()).isEqualTo(10L);
        assertThat(rows.getFirst().promptTokens()).isEqualTo(8L);
        assertThat(rows.getFirst().completionTokens()).isEqualTo(2L);
        assertThat(rows.getFirst().spend()).isNull();
        assertThat(rows.getFirst().model()).isNull();
        assertThat(rows.getFirst().modelGroup()).isNull();
    }

    @Test
    void modelConfig_mapsPricingFromV2ModelInfoResponse() throws Exception {
        String entry = """
                {"model_name":"bedrock-aws-bedrock-minimax-minimax-m2-5-838d9bd6",
                 "litellm_params":{"model":"minimax.minimax-m2.5","custom_llm_provider":"openai"},
                 "model_info":{"mode":"chat","input_cost_per_token":4.7E-7,"output_cost_per_token":1.86E-6,
                               "id":"abc","db_model":true,"blocked":false}}""";
        ObjectMapper objectMapper = new ObjectMapper();

        ModelConfig config = objectMapper.readValue(entry, ModelConfig.class);

        assertThat(config.modelName()).isEqualTo("bedrock-aws-bedrock-minimax-minimax-m2-5-838d9bd6");
        assertThat(config.modelInfo()).isNotNull();
        assertThat(config.modelInfo().mode()).isEqualTo("chat");
        assertThat(config.modelInfo().inputCostPerToken()).isEqualTo(4.7e-07);
        assertThat(config.modelInfo().outputCostPerToken()).isEqualTo(1.86e-06);
    }

    @Test
    void modelInfo_serializesPricingWithLiteLLMFieldNames() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper.writeValueAsString(new ModelInfo("chat", 3.0e-07, 1.2e-06));

        Map<?, ?> map = objectMapper.readValue(json, Map.class);
        assertThat(map.get("mode")).isEqualTo("chat");
        assertThat(map.get("input_cost_per_token")).isEqualTo(3.0e-07);
        assertThat(map.get("output_cost_per_token")).isEqualTo(1.2e-06);
    }

    @Test
    void liteLLMParams_serializesBaseModel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper.writeValueAsString(
                new LiteLLMParams("deploy", "key", "azure", "https://r.openai.azure.com", "gpt-4o"));

        Map<?, ?> map = objectMapper.readValue(json, Map.class);
        assertThat(map.get("model")).isEqualTo("deploy");
        assertThat(map.get("custom_llm_provider")).isEqualTo("azure");
        assertThat(map.get("base_model")).isEqualTo("gpt-4o");
    }

    @Test
    void modelCostEntry_mapsRatesByKeyFromCostMapResponse() throws Exception {
        String json = """
                {"bedrock/eu-west-2/minimax.minimax-m2.5":
                 {"input_cost_per_token":4.7E-7,"output_cost_per_token":1.86E-6,
                  "litellm_provider":"bedrock_converse","mode":"chat","max_tokens":1000000}}""";
        ObjectMapper objectMapper = new ObjectMapper();

        Map<String, ModelCostEntry> map =
                objectMapper.readValue(json, new TypeReference<Map<String, ModelCostEntry>>() {});

        assertThat(map).containsOnlyKeys("bedrock/eu-west-2/minimax.minimax-m2.5");
        assertThat(map.get("bedrock/eu-west-2/minimax.minimax-m2.5").inputCostPerToken())
                .isEqualTo(4.7e-07);
        assertThat(map.get("bedrock/eu-west-2/minimax.minimax-m2.5").outputCostPerToken())
                .isEqualTo(1.86e-06);
    }
}
