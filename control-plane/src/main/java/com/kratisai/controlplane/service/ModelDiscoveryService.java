package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.ModelEntryDto;
import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ProviderType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class ModelDiscoveryService {

    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_GROQ_URL = "https://api.groq.com/openai/v1";
    private static final String DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MISTRAL_URL = "https://api.mistral.ai/v1";
    private static final String DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com";
    private static final String DEFAULT_GOOGLE_URL = "https://generativelanguage.googleapis.com";
    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String AZURE_OPENAI_API_VERSION = "2023-03-15-preview";

    private final ModelDiscoveryClient modelDiscoveryClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ModelDiscoveryService(ModelDiscoveryClient modelDiscoveryClient) {
        this.modelDiscoveryClient = modelDiscoveryClient;
    }

    public List<ModelEntryDto> discoverModels(ProviderType providerType, String apiKey, String baseUrl) {
        return switch (providerType) {
            case OPENAI -> discoverOpenAiModels(apiKey, baseUrl);
            case AZURE_OPENAI -> discoverAzureOpenAiModels(apiKey, baseUrl);
            case GROQ -> discoverGroqModels(apiKey, baseUrl);
            case ANTHROPIC -> discoverAnthropicModels(apiKey, baseUrl);
            case OLLAMA -> discoverOllamaModels(baseUrl);
            case MISTRAL -> discoverMistralModels(apiKey, baseUrl);
            case DEEPSEEK -> discoverDeepSeekModels(apiKey, baseUrl);
            case GOOGLE -> discoverGoogleGenAiModels(apiKey, baseUrl);
            // Bedrock API keys are standard bearer tokens served from an OpenAI-compatible endpoint
            case BEDROCK, OTHER -> discoverOpenAiCompatibleModels(apiKey, baseUrl);
        };
    }

    private List<ModelEntryDto> discoverOpenAiModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_OPENAI_URL) + "/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri, "Bearer " + apiKey);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover OpenAI models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverGroqModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_GROQ_URL) + "/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri, "Bearer " + apiKey);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Groq models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Groq models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverAnthropicModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_ANTHROPIC_URL) + "/v1/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModelsWithXApiKey(uri, apiKey, ANTHROPIC_VERSION);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Anthropic models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverAzureOpenAiModels(String apiKey, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("Azure OpenAI base URL is required for model discovery");
        }
        String modelsUrl = baseUrl + "/openai/deployments?api-version=" + AZURE_OPENAI_API_VERSION;
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModelsWithApiKeyHeader(uri, apiKey);
            return parseAzureDeployments(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Azure OpenAI models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Azure OpenAI models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverOllamaModels(String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_OLLAMA_URL) + "/api/tags";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri);
            return parseNamedModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Ollama models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ollama models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverMistralModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_MISTRAL_URL) + "/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri, "Bearer " + apiKey);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Mistral models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Mistral models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverDeepSeekModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_DEEPSEEK_URL) + "/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri, "Bearer " + apiKey);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover DeepSeek models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DeepSeek models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverGoogleGenAiModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_GOOGLE_URL) + "/v1beta/models";
        try {
            // Google GenAI API requires the API key as a query parameter
            String urlWithKey = modelsUrl + "?key=" + apiKey;
            URI uri = URI.create(urlWithKey);
            String response = modelDiscoveryClient.getModels(uri);
            return parseGoogleModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Google GenAI models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Google GenAI models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> discoverOpenAiCompatibleModels(String apiKey, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("Base URL is required for model discovery on OpenAI-compatible providers");
        }
        String modelsUrl = baseUrl + "/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri, "Bearer " + apiKey);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse models response: " + e.getMessage(), e);
        }
    }

    private List<ModelEntryDto> parseDataArrayModels(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        JsonNode data = root.get("data");
        if (data == null) {
            return List.of();
        }

        List<?> list = objectMapper.convertValue(data, List.class);
        return list.stream()
                .map(m -> {
                    String modelName = ((Map<?, ?>) m).get("id").toString();
                    ModelKind kind = looksLikeEmbeddingModel(modelName) ? ModelKind.EMBEDDING : ModelKind.CHAT;
                    return new ModelEntryDto(modelName, kind);
                })
                .toList();
    }

    private List<ModelEntryDto> parseAzureDeployments(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }

        List<?> list = objectMapper.convertValue(data, List.class);
        return list.stream()
                .map(m -> {
                    Map<?, ?> entry = (Map<?, ?>) m;
                    String deploymentName = entry.get("id").toString();
                    String baseModel =
                            entry.get("model") != null ? entry.get("model").toString() : null;
                    ModelKind kind = looksLikeEmbeddingModel(baseModel != null ? baseModel : deploymentName)
                            ? ModelKind.EMBEDDING
                            : ModelKind.CHAT;
                    return new ModelEntryDto(deploymentName, kind, baseModel);
                })
                .toList();
    }

    private List<ModelEntryDto> parseNamedModels(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        JsonNode models = root.get("models");
        if (models == null) {
            return List.of();
        }

        List<?> list = objectMapper.convertValue(models, List.class);
        return list.stream()
                .map(m -> {
                    String modelName = ((Map<?, ?>) m).get("name").toString();
                    ModelKind kind = looksLikeEmbeddingModel(modelName) ? ModelKind.EMBEDDING : ModelKind.CHAT;
                    return new ModelEntryDto(modelName, kind);
                })
                .toList();
    }

    private List<ModelEntryDto> parseGoogleModels(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        JsonNode models = root.get("models");
        if (models == null) {
            return List.of();
        }

        // Google returns models with names like "models/gemini-2.0-flash"
        // We need to extract just the model ID part after "models/"
        List<?> list = objectMapper.convertValue(models, List.class);
        return list.stream()
                .map(m -> {
                    String fullName = ((Map<?, ?>) m).get("name").toString();
                    // Extract the model ID from "models/gemini-2.0-flash" format
                    String modelName = fullName.contains("/") ? fullName.split("/")[1] : fullName;
                    ModelKind kind = looksLikeEmbeddingModel(modelName) ? ModelKind.EMBEDDING : ModelKind.CHAT;
                    return new ModelEntryDto(modelName, kind);
                })
                .toList();
    }

    /**
     * Determines if a model name looks like an embedding model based on common naming patterns.
     */
    public boolean looksLikeEmbeddingModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return lower.contains("embed") || lower.contains("bge") || lower.contains("titan");
    }
}
