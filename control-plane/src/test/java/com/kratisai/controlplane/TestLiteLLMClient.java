package com.kratisai.controlplane;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLiteLLMClient implements LiteLLMClient {

    private static final Logger logger = LoggerFactory.getLogger(TestLiteLLMClient.class);

    private final LiteLLMClient delegate;
    private final Set<String> trackedModels = ConcurrentHashMap.newKeySet();

    public TestLiteLLMClient(LiteLLMClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public AddModelResponse addModel(AddModelRequest request) {
        AddModelResponse response = delegate.addModel(request);
        trackedModels.add(request.modelName());
        logger.debug("Tracked model: {}", request.modelName());
        return response;
    }

    @Override
    public DeleteModelResponse deleteModel(DeleteModelRequest request) {
        DeleteModelResponse response = delegate.deleteModel(request);
        trackedModels.remove(request.id());
        logger.debug("Untracked model: {}", request.id());
        return response;
    }

    // Warning - exposes all models for all teams, with poor performance.  Avoid where possible!
    @Override
    public ListModelsResponse listModels() {
        return delegate.listModels();
    }

    @Override
    public ListModelsV2Response listModelByName(String modelName) {
        return delegate.listModelByName(modelName);
    }

    @Override
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        return delegate.chatCompletion(request);
    }

    @Override
    public GenerateKeyResponse generateKey(GenerateKeyRequest request) {
        return delegate.generateKey(request);
    }

    @Override
    public UpdateKeyResponse updateKey(UpdateKeyRequest request) {
        return delegate.updateKey(request);
    }

    @Override
    public DeleteKeyResponse deleteKey(DeleteKeyRequest request) {
        return delegate.deleteKey(request);
    }

    @Override
    public KeyInfoResponse keyInfo(String key) {
        return delegate.keyInfo(key);
    }

    @Override
    public List<SpendLogEntry> spendLogs(String apiKey) {
        return delegate.spendLogs(apiKey);
    }

    @Override
    public Map<String, ModelCostEntry> modelCostMap() {
        return delegate.modelCostMap();
    }

    public void cleanupTrackedModels() {
        if (trackedModels.isEmpty()) {
            return;
        }

        logger.debug("Cleaning up {} tracked LiteLLM models", trackedModels.size());
        for (String modelName : trackedModels) {
            try {
                ListModelsV2Response response = delegate.listModelByName(modelName);
                if (response == null || response.data() == null) {
                    continue;
                }
                for (ModelConfig config : response.data()) {
                    if (modelName.equals(config.modelName())
                            && config.modelInfo() != null
                            && config.modelInfo().id() != null) {
                        // /model/delete requires the internal model id, not the model_name alias.
                        delegate.deleteModel(
                                new DeleteModelRequest(config.modelInfo().id()));
                        logger.debug("Cleaned up tracked LiteLLM model: {}", modelName);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to cleanup tracked LiteLLM model {}: {}", modelName, e.getMessage());
            }
        }
        trackedModels.clear();
    }
}
