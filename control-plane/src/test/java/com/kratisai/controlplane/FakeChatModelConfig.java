package com.kratisai.controlplane;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.planningagent.MockEmbeddingModel;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.EmbeddingModelFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeChatModelConfig {

    private static final ThreadLocal<Boolean> useRealModel = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> useRealEmbeddingModel = ThreadLocal.withInitial(() -> false);

    public static void setUseRealModel(boolean real) {
        useRealModel.set(real);
    }

    public static boolean isUseRealModel() {
        return useRealModel.get();
    }

    public static void setUseRealEmbeddingModel(boolean real) {
        useRealEmbeddingModel.set(real);
    }

    public static boolean isUseRealEmbeddingModel() {
        return useRealModel.get() || useRealEmbeddingModel.get();
    }

    public static void clear() {
        useRealModel.remove();
        useRealEmbeddingModel.remove();
    }

    @Bean
    @Primary
    public ChatModelFactory fakeChatModelFactory(FakeChatModel chatModel, LiteLLMProperties liteLLMProperties) {
        return new ChatModelFactory(liteLLMProperties) {
            @Override
            public ChatModel createChatModel(ModelProvider provider, String modelNameOverride) {
                if (useRealModel.get()) {
                    return super.createChatModel(provider, modelNameOverride);
                }
                chatModel.setLastModelNameOverride(modelNameOverride);
                return chatModel;
            }

            @Override
            public ChatModel createChatModel(ModelProvider provider, String modelNameOverride, boolean enableThinking) {
                if (useRealModel.get()) {
                    return super.createChatModel(provider, modelNameOverride, enableThinking);
                }
                chatModel.setLastModelNameOverride(modelNameOverride);
                return chatModel;
            }

            @Override
            public ChatModel createChatModelViaLiteLLM(
                    ModelProvider provider, String litellmModelName, String virtualKey, boolean enableThinking) {
                if (useRealModel.get()) {
                    return super.createChatModelViaLiteLLM(provider, litellmModelName, virtualKey, enableThinking);
                }
                chatModel.setLastModelNameOverride(litellmModelName);
                return chatModel;
            }
        };
    }

    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    @Bean
    @Primary
    public EmbeddingModelFactory fakeEmbeddingModelFactory(
            MockEmbeddingModel mockEmbeddingModel, LiteLLMProperties liteLLMProperties) {
        return new EmbeddingModelFactory(liteLLMProperties) {
            @Override
            public EmbeddingModel createEmbeddingModel(ModelProvider provider, String modelNameOverride) {
                if (isUseRealEmbeddingModel()) {
                    return super.createEmbeddingModel(provider, modelNameOverride);
                }
                mockEmbeddingModel.setLastModelNameOverride(modelNameOverride);
                return mockEmbeddingModel;
            }

            @Override
            public EmbeddingModel createEmbeddingModelViaLiteLLM(
                    ModelProvider provider, String litellmModelName, String virtualKey) {
                if (isUseRealEmbeddingModel()) {
                    return super.createEmbeddingModelViaLiteLLM(provider, litellmModelName, virtualKey);
                }
                mockEmbeddingModel.setLastModelNameOverride(litellmModelName);
                return mockEmbeddingModel;
            }
        };
    }
}
