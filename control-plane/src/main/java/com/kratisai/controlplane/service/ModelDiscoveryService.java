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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class ModelDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(ModelDiscoveryService.class);

    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_GROQ_URL = "https://api.groq.com/openai/v1";
    private static final String DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MISTRAL_URL = "https://api.mistral.ai/v1";
    private static final String DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com";
    private static final String DEFAULT_GOOGLE_URL = "https://generativelanguage.googleapis.com";
    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String DEFAULT_KILO_URL = "https://api.kilo.ai/api/gateway";

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String AZURE_OPENAI_API_VERSION = "2023-03-15-preview";

    private static final List<String> CONTEXT_WINDOW_FIELDS = List.of(
            "inputTokenLimit", // Google GenAI
            "context_window", // Groq
            "max_context_length", // Mistral
            "max_model_len", // vLLM and other OpenAI-compatible servers
            "max_input_tokens", // LiteLLM-style gateways
            "context_length");

    /** Capability object keys that publish chat support: Mistral, then Azure deployments. */
    private static final List<String> CHAT_CAPABILITY_KEYS = List.of("completion_chat", "chat_completion");

    /** Capability object keys that publish embedding support: Azure deployments, then Mistral. */
    private static final List<String> EMBEDDING_CAPABILITY_KEYS = List.of("embeddings", "completion_embedding");

    /**
     * Agent-typed Google model families that only serve the Interactions API. Google's ListModels
     * still advertises generateContent for them (verified against the live API, Oct 2026), so the
     * capability metadata is unusable here and calls fail with "This model only supports
     * Interactions API."
     */
    private static final List<String> GOOGLE_AGENT_FAMILY_PREFIXES = List.of("antigravity", "deep-research");

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
            case KILO -> discoverKiloModels(apiKey, baseUrl);
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

    // The Kilo Gateway serves /models with no auth, but a Bearer key is accepted and keeps the
    // discovery flow uniform with the other OpenAI-compatible providers
    private List<ModelEntryDto> discoverKiloModels(String apiKey, String baseUrl) {
        String modelsUrl = (baseUrl != null ? baseUrl : DEFAULT_KILO_URL) + "/models";
        try {
            URI uri = URI.create(modelsUrl);
            String response = modelDiscoveryClient.getModels(uri, "Bearer " + apiKey);
            return parseDataArrayModels(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to discover Kilo models: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Kilo models response: " + e.getMessage(), e);
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
                .map(m -> (Map<?, ?>) m)
                .map(entry -> classifyModel(entry.get("id").toString(), null, entry))
                .flatMap(Optional::stream)
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
                .map(m -> (Map<?, ?>) m)
                .map(entry -> classifyModel(entry.get("id").toString(), entry.get("model"), entry))
                .flatMap(Optional::stream)
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
                .map(m -> (Map<?, ?>) m)
                .map(entry -> classifyModel(entry.get("name").toString(), null, entry))
                .flatMap(Optional::stream)
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
                .map(m -> (Map<?, ?>) m)
                .map(entry -> {
                    String fullName = entry.get("name").toString();
                    // Extract the model ID from "models/gemini-2.0-flash" format
                    String modelName = fullName.contains("/") ? fullName.split("/")[1] : fullName;
                    if (isInteractionsOnlyAgentModel(modelName)) {
                        logger.debug("Dropping Interactions-only agent model '{}' from discovery", modelName);
                        return Optional.<ModelEntryDto>empty();
                    }
                    return classifyModel(modelName, null, entry);
                })
                .flatMap(Optional::stream)
                .toList();
    }

    private static boolean isInteractionsOnlyAgentModel(String modelName) {
        String lower = modelName.toLowerCase();
        return GOOGLE_AGENT_FAMILY_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private Optional<ModelEntryDto> classifyModel(String modelName, Object baseModelObject, Map<?, ?> entry) {
        String baseModel = baseModelObject != null ? baseModelObject.toString() : null;
        String classifyName = baseModel != null ? baseModel : modelName;
        boolean embedding = advertisesEmbeddingSupport(entry) || looksLikeEmbeddingModel(classifyName);
        Optional<Boolean> chatSupport = advertisedChatSupport(entry);
        if (!embedding && chatSupport.isPresent() && !chatSupport.get()) {
            logger.debug("Dropping model '{}' from discovery: published capabilities exclude chat", modelName);
            return Optional.empty();
        }
        ModelKind kind = embedding ? ModelKind.EMBEDDING : ModelKind.CHAT;
        return Optional.of(new ModelEntryDto(modelName, kind, baseModel, extractContextWindow(entry)));
    }

    private static boolean advertisesEmbeddingSupport(Map<?, ?> entry) {
        Object capabilities = entry.get("capabilities");
        if (capabilities instanceof List<?> flags) {
            return flags.contains("embedding");
        }
        if (capabilities instanceof Map<?, ?> flags) {
            return EMBEDDING_CAPABILITY_KEYS.stream().anyMatch(key -> Boolean.TRUE.equals(flags.get(key)));
        }
        return false;
    }

    /**
     * Reads the capability metadata each provider publishes, so discovery never offers models that
     * would fail at call time (Google Interactions-only agents such as Antigravity omit
     * generateContent; Ollama, Mistral, Azure, and OpenRouter-style gateways publish their own
     * equivalents). Empty means the provider advertised nothing about chat support, which is the
     * case for sparse listings (OpenAI, Groq, DeepSeek) — those models are kept.
     */
    private static Optional<Boolean> advertisedChatSupport(Map<?, ?> entry) {
        Optional<Boolean> generationMethods = googleGenerationSupport(entry);
        if (generationMethods.isPresent()) {
            return generationMethods;
        }
        Optional<Boolean> capabilities = capabilityFlagsSupport(entry);
        if (capabilities.isPresent()) {
            return capabilities;
        }
        return outputModalitySupport(entry);
    }

    private static Optional<Boolean> googleGenerationSupport(Map<?, ?> entry) {
        if (!(entry.get("supportedGenerationMethods") instanceof List<?> methods)) {
            return Optional.empty();
        }
        return Optional.of(methods.contains("generateContent") || methods.contains("streamGenerateContent"));
    }

    private static Optional<Boolean> capabilityFlagsSupport(Map<?, ?> entry) {
        Object capabilities = entry.get("capabilities");
        if (capabilities instanceof List<?> flags) {
            // Ollama publishes an array; only models with "completion" serve chat.
            return Optional.of(flags.contains("completion"));
        }
        if (capabilities instanceof Map<?, ?> flags) {
            for (String key : CHAT_CAPABILITY_KEYS) {
                if (flags.get(key) instanceof Boolean chatSupported) {
                    return Optional.of(chatSupported);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Boolean> outputModalitySupport(Map<?, ?> entry) {
        List<?> modalities = null;
        if (entry.get("architecture") instanceof Map<?, ?> architecture) {
            // Kilo and other OpenRouter-style gateways nest modalities under "architecture".
            if (architecture.get("output_modalities") instanceof List<?> nested) {
                modalities = nested;
            }
        }
        if (modalities == null && entry.get("output_modalities") instanceof List<?> topLevel) {
            modalities = topLevel;
        }
        return modalities != null ? Optional.of(modalities.contains("text")) : Optional.empty();
    }

    private Long extractContextWindow(Map<?, ?> entry) {
        for (String field : CONTEXT_WINDOW_FIELDS) {
            Long tokens = toPositiveLong(entry.get(field));
            if (tokens != null) {
                return tokens;
            }
        }
        return null;
    }

    private static Long toPositiveLong(Object value) {
        if (value instanceof Number number) {
            long tokens = number.longValue();
            return tokens > 0 ? tokens : null;
        }
        if (value instanceof String text) {
            try {
                long tokens = Long.parseLong(text.trim());
                return tokens > 0 ? tokens : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
