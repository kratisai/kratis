package com.kratisai.controlplane.service;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderModel;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

@Service
public class LiteLLMProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(LiteLLMProvisioningService.class);

    private static final int MAX_STARTUP_RECONCILE_RETRIES = 12;

    // LiteLLM returns an HTTP 500 (rather than an empty list) from `/model/info` when no models
    // have been configured on the proxy yet -  This is expected
    private static final String EMPTY_MODEL_LIST_ERROR_MARKER = "Model List not loaded in";

    // bedrock-runtime.us-east-1.amazonaws.com, bedrock-mantle.eu-west-2.api.aws, ...
    private static final Pattern AWS_REGION_PATTERN = Pattern.compile("[a-z]{2}-[a-z]+-[0-9]+");

    public enum VirtualKeyScope {
        SANDBOX,
        INGESTION
    }

    private static final Map<ProviderType, String> PROVIDER_MAPPING = Map.ofEntries(
            Map.entry(ProviderType.OPENAI, "openai"),
            Map.entry(ProviderType.ANTHROPIC, "anthropic"),
            Map.entry(ProviderType.GOOGLE, "gemini"),
            Map.entry(ProviderType.GROQ, "groq"),
            Map.entry(ProviderType.MISTRAL, "mistral"),
            Map.entry(ProviderType.DEEPSEEK, "deepseek"),
            Map.entry(ProviderType.OLLAMA, "ollama"),
            Map.entry(ProviderType.AZURE_OPENAI, "azure"),
            Map.entry(ProviderType.BEDROCK, "openai"));

    private final LiteLLMClient liteLLMClient;
    private final ModelProviderRepository modelProviderRepository;
    private final TeamRepository teamRepository;
    private final boolean reconcileOnStartup;

    private final AtomicBoolean reconciliationSettled = new AtomicBoolean(false);
    private final AtomicInteger reconcileFailures = new AtomicInteger(0);

    public LiteLLMProvisioningService(
            LiteLLMClient liteLLMClient,
            ModelProviderRepository modelProviderRepository,
            TeamRepository teamRepository,
            @Value("${kratis.litellm.reconcile-on-startup:true}") boolean reconcileOnStartup) {
        this.liteLLMClient = liteLLMClient;
        this.modelProviderRepository = modelProviderRepository;
        this.teamRepository = teamRepository;
        this.reconcileOnStartup = reconcileOnStartup;
    }

    @PostConstruct
    void reconcileOnStartup() {
        if (!reconcileOnStartup) {
            reconciliationSettled.set(true);
            logger.info("LiteLLM startup reconciliation skipped (disabled by configuration)");
            return;
        }
        try {
            Set<String> existingModels = listExistingLiteLLMModels();
            ModelCostLookup costLookup = new ModelCostLookup();
            for (ModelProvider provider : modelProviderRepository.findAll()) {
                for (ProviderModel providerModel : provider.getModels()) {
                    String modelName = providerModel.getModelName();
                    String litellmName = buildLiteLLMModelName(provider, modelName);
                    if (!existingModels.contains(litellmName)
                            || needsPricingOverride(provider, modelName, costLookup)) {
                        logger.info("Reconciling model '{}' on startup", litellmName);
                        provisionSingleModel(provider, providerModel, costLookup);
                    }
                }
            }

            for (Team team : teamRepository.findAllWithIngestionAndEmbeddingProviders()) {
                ensureModelRegistered(
                        team.getIngestionProvider(),
                        team.getIngestionModel(),
                        ModelKind.CHAT,
                        existingModels,
                        costLookup);
                ensureModelRegistered(
                        team.getEmbeddingProvider(),
                        team.getEmbeddingModel(),
                        ModelKind.EMBEDDING,
                        existingModels,
                        costLookup);
            }

            reconciliationSettled.set(true);
            logger.info("LiteLLM startup reconciliation complete");
        } catch (RestClientException e) {
            // LiteLLM may not be reachable yet. The container can be running while the LiteLLM
            // is still booting. Startup must not fail because of it; retryReconciliation()
            // keeps trying until LiteLLM is reachable.
            int failureCount = reconcileFailures.incrementAndGet();
            logger.warn(
                    "LiteLLM unavailable during startup reconciliation (attempt {}/{}), skipping. The application "
                            + "will continue starting. Cause: {}",
                    failureCount,
                    MAX_STARTUP_RECONCILE_RETRIES,
                    e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${kratis.litellm.reconcile-retry-delay-ms:10000}")
    void retryReconciliation() {
        if (!reconcileOnStartup || reconciliationSettled.get()) {
            return;
        }
        if (reconcileFailures.get() >= MAX_STARTUP_RECONCILE_RETRIES) {
            logger.warn(
                    "Giving up on LiteLLM startup reconciliation after {} failed attempts. Models will need to be "
                            + "reconciled manually once LiteLLM is available.",
                    reconcileFailures.get());
            reconciliationSettled.set(true);
            return;
        }
        logger.info("Retrying LiteLLM startup reconciliation (LiteLLM may still have been starting up)");
        reconcileOnStartup();
    }

    public void ensureModelRegistered(ModelProvider provider, String modelName, ModelKind kind) {
        ensureModelRegistered(provider, modelName, kind, null, new ModelCostLookup());
    }

    public void ensureModelsRegistered(
            ModelProvider chatProvider, String chatModel, ModelProvider embeddingProvider, String embeddingModel) {
        ModelCostLookup costLookup = new ModelCostLookup();
        ensureModelRegistered(chatProvider, chatModel, ModelKind.CHAT, null, costLookup);
        ensureModelRegistered(embeddingProvider, embeddingModel, ModelKind.EMBEDDING, null, costLookup);
    }

    private Set<String> listExistingLiteLLMModels() {
        ListModelsResponse response = fetchModelList();
        if (response == null || response.data() == null) {
            return Set.of();
        }
        return response.data().stream().map(ModelConfig::modelName).collect(Collectors.toSet());
    }

    private @Nullable ListModelsResponse fetchModelList() {
        try {
            return liteLLMClient.listModels();
        } catch (HttpServerErrorException e) {
            if (isEmptyModelListError(e)) {
                logger.info("LiteLLM has no models configured yet");
                return null;
            }
            throw e;
        }
    }

    private boolean isEmptyModelListError(HttpServerErrorException e) {
        String body = e.getResponseBodyAsString();
        return body.contains(EMPTY_MODEL_LIST_ERROR_MARKER);
    }

    private boolean isModelRegisteredInLiteLLM(String litellmModelName) {
        try {
            ListModelsV2Response response = liteLLMClient.listModelByName(litellmModelName);
            return response.data() != null && !response.data().isEmpty();
        } catch (RestClientException e) {
            logger.debug("Error checking model '{}' in LiteLLM: {}", litellmModelName, e.getMessage());
            return false;
        }
    }

    private void ensureModelRegistered(
            ModelProvider provider,
            String modelName,
            ModelKind kind,
            @Nullable Set<String> knownExistingLiteLLMModels,
            ModelCostLookup costLookup) {
        if (provider == null || modelName == null || modelName.isBlank()) {
            return;
        }

        ProviderModel providerModel = provider.getModels().stream()
                .filter(pm -> pm.getModelName().equals(modelName) && pm.getKind() == kind)
                .findFirst()
                .orElse(null);

        boolean alreadyOnProvider = providerModel != null;

        if (providerModel == null) {
            providerModel = new ProviderModel(modelName, kind);
            List<ProviderModel> updatedModels = new ArrayList<>(provider.getModels());
            updatedModels.add(providerModel);
            provider.setModels(updatedModels);
            modelProviderRepository.save(provider);
        }

        boolean registeredInLiteLLM = knownExistingLiteLLMModels != null
                ? knownExistingLiteLLMModels.contains(buildLiteLLMModelName(provider, modelName))
                : isModelRegisteredInLiteLLM(buildLiteLLMModelName(provider, modelName));

        // Self-heal: LiteLLM lost the model, or (on startup) the deployment is missing the
        // Bedrock cost override LiteLLM cannot apply itself.
        if (!alreadyOnProvider
                || !registeredInLiteLLM
                || (knownExistingLiteLLMModels != null && needsPricingOverride(provider, modelName, costLookup))) {
            provisionSingleModel(provider, providerModel, costLookup);
        }
    }

    public void provisionModel(ModelProvider provider) {
        String litellmProvider = resolveLiteLLMProvider(provider.getProviderType());
        if (litellmProvider == null) {
            logger.warn(
                    "Provider type '{}' is not supported by LiteLLM, skipping provisioning",
                    provider.getProviderType());
            return;
        }

        List<ProviderModel> models = provider.getModels();
        if (models.isEmpty()) {
            logger.warn(
                    "Provider '{}' has no model names configured, skipping provisioning", provider.getDisplayName());
            return;
        }

        ModelCostLookup costLookup = new ModelCostLookup();
        for (ProviderModel providerModel : models) {
            provisionSingleModel(provider, providerModel, costLookup);
        }
    }

    private void deleteExistingDeployments(String litellmName) {
        Set<String> deletedIds = new HashSet<>(); // Delete each ID only once.
        try {
            // LiteLLM pages at 50
            while (true) {
                ListModelsV2Response response = liteLLMClient.listModelByName(litellmName);
                if (response == null
                        || response.data() == null
                        || response.data().isEmpty()) {
                    return;
                }
                int deletedThisPass = 0;
                for (ModelConfig config : response.data()) {
                    if (!litellmName.equals(config.modelName())
                            || config.modelInfo() == null
                            || config.modelInfo().id() == null
                            || !deletedIds.add(config.modelInfo().id())) {
                        continue;
                    }
                    liteLLMClient.deleteModel(
                            new DeleteModelRequest(config.modelInfo().id()));
                    deletedThisPass++;
                }
                if (deletedThisPass == 0) {
                    return;
                }
            }
        } catch (RestClientException e) {
            logger.debug("No existing deployments for '{}' in LiteLLM: {}", litellmName, e.getMessage());
        }
    }

    private void provisionSingleModel(ModelProvider provider, ProviderModel providerModel, ModelCostLookup costLookup) {
        String modelName = providerModel.getModelName();
        ModelKind kind = providerModel.getKind();
        String litellmProvider = resolveLiteLLMProvider(provider.getProviderType());
        String litellmName = buildLiteLLMModelName(provider, modelName);

        String apiBase =
                provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank() ? provider.getBaseUrl() : null;

        // LiteLLM expects api_base to include the /v1 suffix for OpenAI-compatible APIs.
        // However, for Gemini API format (ProviderType.GOOGLE), the base URL should NOT have /v1 appended
        boolean isGeminiProvider = provider.getProviderType() == ProviderType.GOOGLE;
        if (apiBase != null && !isGeminiProvider && !apiBase.endsWith("/v1") && !apiBase.endsWith("/v1/")) {
            apiBase = apiBase + "/v1";
        }

        LiteLLMParams params = new LiteLLMParams(
                modelName, provider.getApiKey(), litellmProvider, apiBase, providerModel.getBaseModel());
        ModelInfo modelInfo = buildModelInfo(provider, modelName, kind, costLookup);
        AddModelRequest request = new AddModelRequest(litellmName, params, modelInfo);

        deleteExistingDeployments(litellmName);
        logger.info(
                "Provisioning model '{}' as '{}' (provider: {}) to LiteLLM", modelName, litellmName, litellmProvider);
        liteLLMClient.addModel(request);
        logger.info("Successfully provisioned model '{}' to LiteLLM", litellmName);
    }

    public void removeModel(ModelProvider provider) {
        for (String modelName : provider.getModelNames()) {
            String litellmName = buildLiteLLMModelName(provider, modelName);
            logger.info("Removing model '{}' from LiteLLM", litellmName);
            deleteExistingDeployments(litellmName);
        }
    }

    public boolean verifyModelRegistered(ModelProvider provider) {
        ListModelsResponse response = fetchModelList();
        if (response == null || response.data() == null) {
            return false;
        }
        Set<String> existingModels =
                response.data().stream().map(ModelConfig::modelName).collect(Collectors.toSet());

        for (String modelName : provider.getModelNames()) {
            String litellmName = buildLiteLLMModelName(provider, modelName);
            if (!existingModels.contains(litellmName)) {
                return false;
            }
        }
        return !provider.getModelNames().isEmpty();
    }

    public boolean verifyModelRegistered(ModelProvider provider, String modelName) {
        return isModelRegisteredInLiteLLM(buildLiteLLMModelName(provider, modelName));
    }

    public String resolveLiteLLMProvider(ProviderType providerType) {
        return PROVIDER_MAPPING.get(providerType);
    }

    public String buildLiteLLMModelName(ModelProvider provider, String modelName) {
        Objects.requireNonNull(provider.getTeam(), "ModelProvider must have an associated team");
        String sanitizedProvider = provider.getDisplayName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String sanitizedModel = modelName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String teamSuffix = provider.getTeam().getId().toString().substring(0, 8);
        return provider.getProviderType().name().toLowerCase()
                + "-" + sanitizedProvider
                + "-" + sanitizedModel
                + "-" + teamSuffix;
    }

    public String buildVirtualKeyAlias(VirtualKeyScope scope, UUID ownerId) {
        Objects.requireNonNull(scope, "scope is required");
        Objects.requireNonNull(ownerId, "ownerId is required");
        return "kratis-" + scope.name().toLowerCase() + "-" + ownerId;
    }

    private ModelInfo buildModelInfo(
            ModelProvider provider, String modelName, ModelKind kind, ModelCostLookup costLookup) {
        String mode = kind == ModelKind.EMBEDDING ? "embedding" : "chat";
        ModelCostEntry entry = resolveModelCost(provider, modelName, costLookup);
        if (entry == null || (entry.inputCostPerToken() == null && entry.outputCostPerToken() == null)) {
            return new ModelInfo(mode);
        }
        return new ModelInfo(mode, entry.inputCostPerToken(), entry.outputCostPerToken());
    }

    private boolean needsPricingOverride(ModelProvider provider, String modelName, ModelCostLookup costLookup) {
        return resolveModelCost(provider, modelName, costLookup) != null;
    }

    // Kratis uses bedrock-mantis openai-api, auth via API key. LiteLLM doesn't price bedrock-mantis calls.
    private @Nullable ModelCostEntry resolveModelCost(
            ModelProvider provider, String modelName, ModelCostLookup costLookup) {
        if (provider.getProviderType() != ProviderType.BEDROCK) {
            return null;
        }
        Map<String, ModelCostEntry> entries = costLookup.entries();
        for (String candidate : bedrockPricingCandidates(provider, modelName)) {
            ModelCostEntry entry = entries.get(candidate);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private List<String> bedrockPricingCandidates(ModelProvider provider, String modelName) {
        List<String> candidates = new ArrayList<>(3);
        String region = extractAwsRegion(provider.getBaseUrl());
        if (region != null) {
            candidates.add("bedrock/" + region + "/" + modelName);
        }
        candidates.add("bedrock/" + modelName);
        candidates.add(modelName);
        return candidates;
    }

    private Map<String, ModelCostEntry> fetchModelCostMap() {
        try {
            Map<String, ModelCostEntry> entries = liteLLMClient.modelCostMap();
            return entries != null ? entries : Map.of();
        } catch (RestClientException e) {
            logger.warn(
                    "Could not fetch LiteLLM's model cost map; affected models log $0 spend until the next "
                            + "provisioning. Cause: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    private static @Nullable String extractAwsRegion(@Nullable String apiBase) {
        if (apiBase == null) {
            return null;
        }
        Matcher matcher = AWS_REGION_PATTERN.matcher(apiBase);
        return matcher.find() ? matcher.group() : null;
    }

    // short-lived lazy lookup of LiteLLM's model-costs
    private final class ModelCostLookup {

        private @Nullable Map<String, ModelCostEntry> entries;

        private Map<String, ModelCostEntry> entries() {
            if (entries == null) {
                entries = fetchModelCostMap();
            }
            return entries;
        }
    }
}
