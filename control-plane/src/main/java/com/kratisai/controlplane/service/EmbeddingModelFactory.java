package com.kratisai.controlplane.service;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmbeddingModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingModelFactory.class);

    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";

    private final LiteLLMProperties liteLLMProperties;

    public record EmbeddingModelSpec(ProviderType providerType, String modelName, String apiKey, String baseUrl) {}

    @Autowired
    public EmbeddingModelFactory(LiteLLMProperties liteLLMProperties) {
        this.liteLLMProperties = liteLLMProperties;
    }

    public EmbeddingModel createEmbeddingModel(ModelProvider provider, String modelName) {
        validate(provider, modelName, "modelName");
        EmbeddingModelSpec spec = buildDirectSpec(provider, modelName);
        return createEmbeddingModel(spec);
    }

    public EmbeddingModel createEmbeddingModelViaLiteLLM(
            ModelProvider provider, String litellmModelName, String virtualKey) {
        validate(provider, litellmModelName, "litellmModelName");
        if (!StringUtils.hasText(virtualKey)) {
            throw new IllegalArgumentException("virtualKey is required and must be specified");
        }
        EmbeddingModelSpec spec = buildLiteLLMSpec(litellmModelName, virtualKey);
        return createEmbeddingModel(spec);
    }

    private EmbeddingModel createEmbeddingModel(EmbeddingModelSpec spec) {
        logger.debug("Creating {} Embedding model via {}", spec.providerType(), spec.baseUrl());
        return switch (spec.providerType()) {
            case GOOGLE -> createGoogleEmbeddingModel(spec);
            default -> createOpenAiCompatibleEmbeddingModel(spec);
        };
    }

    private EmbeddingModelSpec buildDirectSpec(ModelProvider provider, String modelName) {
        if (StringUtils.hasText(provider.getBaseUrl()) && !DEFAULT_OPENAI_URL.equals(provider.getBaseUrl())) {
            return new EmbeddingModelSpec(ProviderType.OPENAI, modelName, provider.getApiKey(), provider.getBaseUrl());
        }

        String baseUrl =
                switch (provider.getProviderType()) {
                    case OPENAI, AZURE_OPENAI -> getBaseUrl(provider, DEFAULT_OPENAI_URL);
                    default -> provider.getBaseUrl();
                };
        return new EmbeddingModelSpec(provider.getProviderType(), modelName, provider.getApiKey(), baseUrl);
    }

    private EmbeddingModelSpec buildLiteLLMSpec(String modelName, String virtualKey) {
        String baseUrl = liteLLMProperties.getBaseUrl() + "/v1";
        return new EmbeddingModelSpec(ProviderType.OPENAI, modelName, virtualKey, baseUrl);
    }

    private EmbeddingModel createOpenAiCompatibleEmbeddingModel(EmbeddingModelSpec spec) {
        String baseUrl = StringUtils.hasText(spec.baseUrl()) ? spec.baseUrl() : DEFAULT_OPENAI_URL;
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(spec.modelName())
                .apiKey(spec.apiKey())
                .baseUrl(baseUrl)
                .build();
        return new OpenAiEmbeddingModel(options);
    }

    private EmbeddingModel createGoogleEmbeddingModel(EmbeddingModelSpec spec) {
        Client.Builder clientBuilder = Client.builder().apiKey(spec.apiKey());
        if (StringUtils.hasText(spec.baseUrl())) {
            clientBuilder.httpOptions(
                    HttpOptions.builder().baseUrl(spec.baseUrl()).build());
        }
        Client client = clientBuilder.build();
        GoogleGenAiEmbeddingConnectionDetails details = GoogleGenAiEmbeddingConnectionDetails.builder()
                .genAiClient(client)
                .build();
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(spec.modelName())
                .build();
        return new GoogleGenAiTextEmbeddingModel(details, options);
    }

    private String getBaseUrl(ModelProvider provider, String defaultUrl) {
        return StringUtils.hasText(provider.getBaseUrl()) ? provider.getBaseUrl() : defaultUrl;
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
