package com.kratisai.controlplane.client.litellm;

import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange
public interface LiteLLMClient {

    @PostExchange("/model/new")
    AddModelResponse addModel(@RequestBody AddModelRequest request);

    // List all models from the Proxy - no team or model discriminator.  Prefer other methods.
    @GetExchange("/model/info")
    ListModelsResponse listModels();

    @GetExchange("/v2/model/info")
    ListModelsV2Response listModelByName(@RequestParam("model") String modelName);

    @PostExchange("/model/delete")
    DeleteModelResponse deleteModel(@RequestBody DeleteModelRequest request);

    @PostExchange("/v1/chat/completions")
    ChatCompletionResponse chatCompletion(@RequestBody ChatCompletionRequest request);

    @PostExchange("/key/generate")
    GenerateKeyResponse generateKey(@RequestBody GenerateKeyRequest request);

    @PostExchange("/key/update")
    UpdateKeyResponse updateKey(@RequestBody UpdateKeyRequest request);

    @PostExchange("/key/delete")
    DeleteKeyResponse deleteKey(@RequestBody DeleteKeyRequest request);

    @GetExchange("/key/info")
    KeyInfoResponse keyInfo(@RequestParam("key") String key);

    @GetExchange("/spend/logs")
    List<SpendLogEntry> spendLogs(@RequestParam("api_key") String apiKey);
}
