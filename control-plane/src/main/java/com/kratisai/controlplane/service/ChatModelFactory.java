package com.kratisai.controlplane.service;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(ChatModelFactory.class);

    private final LiteLLMProperties liteLLMProperties;

    public record ChatModelSpec(
            ProviderType providerType, String modelName, String apiKey, String baseUrl, boolean enableThinking) {}

    public ChatModelFactory(LiteLLMProperties liteLLMProperties) {
        this.liteLLMProperties = liteLLMProperties;
    }

    public ChatModel createChatModel(ModelProvider provider, String modelNameOverride) {
        return createChatModel(provider, modelNameOverride, false);
    }

    public ChatModel createChatModel(ModelProvider provider, String modelNameOverride, boolean enableThinking) {
        validate(provider, modelNameOverride, "modelNameOverride");
        ChatModelSpec spec = buildDirectSpec(provider, modelNameOverride, enableThinking);
        return createChatModel(spec);
    }

    public ChatModel createChatModelViaLiteLLM(
            ModelProvider provider, String litellmModelName, String virtualKey, boolean enableThinking) {
        validate(provider, litellmModelName, "litellmModelName");
        if (!StringUtils.hasText(virtualKey)) {
            throw new IllegalArgumentException("virtualKey is required and must be specified");
        }
        ChatModelSpec spec = buildLiteLLMSpec(provider, litellmModelName, virtualKey, enableThinking);
        return createChatModel(spec);
    }

    private ChatModel createChatModel(ChatModelSpec spec) {
        logger.debug("Creating {} Chat model via {}", spec.providerType(), spec.baseUrl());
        return switch (spec.providerType()) {
            case ANTHROPIC -> createAnthropicChatModel(spec);
            case GOOGLE -> createGoogleChatModel(spec);
            default -> createOpenAiCompatibleChatModel(spec);
        };
    }

    private ChatModelSpec buildDirectSpec(ModelProvider provider, String modelName, boolean enableThinking) {
        String baseUrl = ModelProviderUrlResolver.resolveDirectBaseUrl(provider);
        return new ChatModelSpec(provider.getProviderType(), modelName, provider.getApiKey(), baseUrl, enableThinking);
    }

    private ChatModelSpec buildLiteLLMSpec(
            ModelProvider provider, String litellmModelName, String virtualKey, boolean enableThinking) {
        String baseUrl = ModelProviderUrlResolver.resolveLiteLLMBaseUrl(
                provider.getProviderType(), liteLLMProperties.getBaseUrl());
        return new ChatModelSpec(provider.getProviderType(), litellmModelName, virtualKey, baseUrl, enableThinking);
    }

    private ChatModel createOpenAiCompatibleChatModel(ChatModelSpec spec) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(spec.modelName())
                .apiKey(spec.apiKey())
                .baseUrl(spec.baseUrl());
        if (spec.enableThinking()) {
            optionsBuilder.reasoningEffort("high");
        }
        return OpenAiChatModel.builder().options(optionsBuilder.build()).build();
    }

    private ChatModel createAnthropicChatModel(ChatModelSpec spec) {
        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .model(spec.modelName())
                .apiKey(spec.apiKey())
                .baseUrl(spec.baseUrl());
        if (spec.enableThinking()) {
            optionsBuilder.thinkingEnabled(8192);
        }
        return AnthropicChatModel.builder().options(optionsBuilder.build()).build();
    }

    // The Google GenAI builder() return type loses its generics; the cast is safe and
    // exercised by the integration tests.
    @SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE")
    private ChatModel createGoogleChatModel(ChatModelSpec spec) {
        Client.Builder clientBuilder = Client.builder().apiKey(spec.apiKey());
        if (StringUtils.hasText(spec.baseUrl())) {
            clientBuilder.httpOptions(
                    HttpOptions.builder().baseUrl(spec.baseUrl()).build());
        }
        Client client = clientBuilder.build();
        GoogleGenAiChatOptions.Builder optionsBuilder =
                GoogleGenAiChatOptions.builder().model(spec.modelName());
        if (spec.enableThinking()) {
            optionsBuilder.includeThoughts(true).thinkingBudget(8192);
        }
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    private void validate(ModelProvider provider, String modelName, String fieldName) {
        if (provider == null) {
            throw new IllegalArgumentException("ModelProvider cannot be null");
        }
        if (!StringUtils.hasText(modelName)) {
            throw new IllegalArgumentException(fieldName + " is required and must be specified");
        }
    }
}
