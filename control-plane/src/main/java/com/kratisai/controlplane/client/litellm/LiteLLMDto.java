package com.kratisai.controlplane.client.litellm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public final class LiteLLMDto {

    private LiteLLMDto() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelInfo(
            @JsonProperty("mode") String mode,
            @JsonProperty("input_cost_per_token") Double inputCostPerToken,
            @JsonProperty("output_cost_per_token") Double outputCostPerToken) {
        public ModelInfo(String mode) {
            this(mode, null, null);
        }
    }

    public record AddModelRequest(
            @JsonProperty("model_name") String modelName,
            @JsonProperty("litellm_params") LiteLLMParams litellmParams,
            @JsonProperty("model_info") ModelInfo modelInfo) {
        public AddModelRequest(String modelName, LiteLLMParams litellmParams) {
            this(modelName, litellmParams, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiteLLMParams(
            String model,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("custom_llm_provider") String customLlmProvider,
            @JsonProperty("api_base") String apiBase,
            @JsonProperty("base_model") String baseModel) {
        public LiteLLMParams(String model, String apiKey, String customLlmProvider, String apiBase) {
            this(model, apiKey, customLlmProvider, apiBase, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddModelResponse(Map<String, Object> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListModelsResponse(List<ModelConfig> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListModelsV2Response(List<ModelConfig> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelCostEntry(
            @JsonProperty("input_cost_per_token") Double inputCostPerToken,
            @JsonProperty("output_cost_per_token") Double outputCostPerToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelConfig(
            @JsonProperty("model_name") String modelName,
            @JsonProperty("litellm_params") LiteLLMParams litellmParams,
            @JsonProperty("model_info") ModelInfo modelInfo) {
        public ModelConfig(String modelName, LiteLLMParams litellmParams) {
            this(modelName, litellmParams, null);
        }
    }

    public record DeleteModelRequest(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteModelResponse(boolean deleted) {}

    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens) {}

    public record ChatMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(String id, List<ChatChoice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatChoice(
            ChatMessage message,
            @JsonProperty("finish_reason") String finishReason) {}

    // --- Virtual Key DTOs ---

    public record GenerateKeyRequest(
            @JsonProperty("key_alias") String keyAlias,
            @JsonProperty("models") List<String> models) {}

    public record UpdateKeyRequest(
            String key, @JsonProperty("models") List<String> models) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateKeyResponse(
            String key, @JsonProperty("key_alias") String keyAlias) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateKeyResponse(
            String key, @JsonProperty("key_alias") String keyAlias) {}

    public record DeleteKeyRequest(
            @JsonProperty("keys") List<String> keys,
            @JsonProperty("key_aliases") List<String> keyAliases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteKeyResponse(
            @JsonProperty("deleted_keys") List<String> deletedKeys, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyInfoData(@JsonProperty("key_alias") String keyAlias, Double spend) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyInfoResponse(
            String key, @JsonProperty("info") KeyInfoData info) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpendLogEntry(
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("prompt_tokens") Long promptTokens,
            @JsonProperty("completion_tokens") Long completionTokens,
            Double spend,
            String model,
            @JsonProperty("model_group") String modelGroup) {
        public SpendLogEntry(Long totalTokens, Long promptTokens, Long completionTokens) {
            this(totalTokens, promptTokens, completionTokens, null, null, null);
        }
    }
}
