package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderModel;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelProviderService {

    private final ModelProviderRepository modelProviderRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final ModelDiscoveryService modelDiscoveryService;
    private final ApplicationEventPublisher eventPublisher;
    private final LiteLLMProvisioningService liteLLMProvisioningService;

    /** Metadata for each supported provider type */
    private static final Map<ProviderType, SupportedProviderTypeDto> PROVIDER_METADATA = createProviderMetadata();

    private static Map<ProviderType, SupportedProviderTypeDto> createProviderMetadata() {
        Map<ProviderType, SupportedProviderTypeDto> map = new HashMap<>();
        map.put(
                ProviderType.OPENAI,
                new SupportedProviderTypeDto(
                        "OPENAI",
                        "OpenAI",
                        "https://api.openai.com/v1",
                        false,
                        true,
                        "OpenAI API (GPT-4, GPT-4o, etc.)"));
        map.put(
                ProviderType.AZURE_OPENAI,
                new SupportedProviderTypeDto("AZURE_OPENAI", "Azure OpenAI", null, true, true, "Azure OpenAI Service"));
        map.put(
                ProviderType.ANTHROPIC,
                new SupportedProviderTypeDto(
                        "ANTHROPIC", "Anthropic", "https://api.anthropic.com", false, true, "Anthropic Claude API"));
        map.put(
                ProviderType.GROQ,
                new SupportedProviderTypeDto(
                        "GROQ",
                        "Groq",
                        "https://api.groq.com/openai/v1",
                        false,
                        true,
                        "Groq Cloud API (OpenAI-compatible)"));
        map.put(
                ProviderType.OLLAMA,
                new SupportedProviderTypeDto(
                        "OLLAMA", "Ollama", "http://localhost:11434", true, false, "Local Ollama instance"));
        map.put(
                ProviderType.MISTRAL,
                new SupportedProviderTypeDto(
                        "MISTRAL", "Mistral AI", "https://api.mistral.ai/v1", false, true, "Mistral AI API"));
        map.put(
                ProviderType.DEEPSEEK,
                new SupportedProviderTypeDto(
                        "DEEPSEEK", "DeepSeek", "https://api.deepseek.com", false, true, "DeepSeek API"));
        map.put(
                ProviderType.GOOGLE,
                new SupportedProviderTypeDto(
                        "GOOGLE",
                        "Google GenAI",
                        "https://generativelanguage.googleapis.com",
                        false,
                        true,
                        "Google Gemini API"));
        map.put(
                ProviderType.BEDROCK,
                new SupportedProviderTypeDto(
                        "BEDROCK",
                        "AWS Bedrock",
                        null,
                        true,
                        true,
                        "AWS Bedrock (OpenAI-compatible endpoint, bearer API key)"));
        map.put(
                ProviderType.OTHER,
                new SupportedProviderTypeDto(
                        "OTHER",
                        "Other (OpenAI-compatible)",
                        null,
                        true,
                        true,
                        "Custom OpenAI-compatible API endpoint"));
        return Map.copyOf(map);
    }

    public ModelProviderService(
            ModelProviderRepository modelProviderRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository,
            ModelDiscoveryService modelDiscoveryService,
            ApplicationEventPublisher eventPublisher,
            LiteLLMProvisioningService liteLLMProvisioningService) {
        this.modelProviderRepository = modelProviderRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.modelDiscoveryService = modelDiscoveryService;
        this.eventPublisher = eventPublisher;
        this.liteLLMProvisioningService = liteLLMProvisioningService;
    }

    /** Returns the list of supported provider types with metadata. */
    public List<SupportedProviderTypeDto> getSupportedTypes() {
        return Arrays.stream(ProviderType.values()).map(PROVIDER_METADATA::get).toList();
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private void requireTeamOwner(UUID userId, UUID teamId) {
        String role = teamMemberRepository
                .findByTeamIdAndUserId(teamId, userId)
                .map(com.kratisai.controlplane.model.TeamMember::getRole)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"));

        if (!"owner".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions");
        }
    }

    /**
     * Discovers available models for an existing model provider by querying the provider's API.
     */
    @Transactional(readOnly = true)
    public List<ModelEntryDto> discoverModelsForProvider(UUID userId, UUID providerId) {
        ModelProvider provider = modelProviderRepository
                .findById(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));

        // Verify user is a member of the team that owns this provider
        if (!teamMemberRepository.existsByTeamIdAndUserId(provider.getTeam().getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }

        return modelDiscoveryService.discoverModels(
                provider.getProviderType(), provider.getApiKey(), provider.getBaseUrl());
    }

    private ModelProviderDto toDto(ModelProvider provider) {
        List<ModelEntryDto> models = provider.getModels().stream()
                .map(pm -> new ModelEntryDto(pm.getModelName(), pm.getKind()))
                .toList();
        return new ModelProviderDto(
                provider.getId(),
                provider.getTeam().getId(),
                provider.getDisplayName(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                provider.isActive(),
                models,
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }

    public List<ModelProviderDto> listModelProviders(UUID userId, UUID teamId) {
        requireTeamMembership(userId, teamId);
        return modelProviderRepository.findByTeamId(teamId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public ModelProviderDto createModelProvider(UUID userId, UUID teamId, CreateModelProviderRequest request) {
        requireTeamOwner(userId, teamId);

        if (modelProviderRepository.existsByTeamIdAndDisplayName(teamId, request.displayName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Model provider name already exists in this team");
        }

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        ModelProvider provider =
                new ModelProvider(request.displayName(), request.providerType(), request.apiKey(), request.baseUrl());
        List<ProviderModel> models = request.models() != null
                ? new ArrayList<>(request.models().stream()
                        .map(dto -> new ProviderModel(dto.modelName(), dto.kind()))
                        .toList())
                : new ArrayList<>();
        provider.setModels(models);
        provider.setTeam(team);
        modelProviderRepository.save(provider);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.MODEL_PROVIDERS));
        liteLLMProvisioningService.provisionModel(provider);
        return toDto(provider);
    }

    public ModelProviderDto getModelProvider(UUID userId, UUID teamId, UUID providerId) {
        requireTeamMembership(userId, teamId);
        ModelProvider provider = modelProviderRepository
                .findByTeamIdAndId(teamId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));
        return toDto(provider);
    }

    @Transactional
    public ModelProviderDto updateModelProvider(
            UUID userId, UUID teamId, UUID providerId, UpdateModelProviderRequest request) {
        requireTeamOwner(userId, teamId);

        ModelProvider provider = modelProviderRepository
                .findByTeamIdAndId(teamId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));

        if (request.displayName() != null) {
            provider.setDisplayName(request.displayName());
        }
        if (request.apiKey() != null) {
            provider.setApiKey(request.apiKey());
        }
        if (request.baseUrl() != null) {
            provider.setBaseUrl(request.baseUrl());
        }
        if (request.isActive() != null) {
            provider.setActive(request.isActive());
        }
        if (request.models() != null) {
            List<ProviderModel> models = new ArrayList<>(request.models().stream()
                    .map(dto -> new ProviderModel(dto.modelName(), dto.kind()))
                    .toList());
            provider.setModels(models);
        }

        modelProviderRepository.save(provider);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.MODEL_PROVIDERS));
        liteLLMProvisioningService.provisionModel(provider);
        return toDto(provider);
    }

    @Transactional
    public void deleteModelProvider(UUID userId, UUID teamId, UUID providerId) {
        requireTeamOwner(userId, teamId);

        ModelProvider provider = modelProviderRepository
                .findByTeamIdAndId(teamId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));

        liteLLMProvisioningService.removeModel(provider);
        modelProviderRepository.delete(provider);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.MODEL_PROVIDERS));
    }

    @Transactional
    public TestConnectionResponse testConnection(TestConnectionRequest request) {
        try {
            List<ModelEntryDto> models =
                    modelDiscoveryService.discoverModels(request.providerType(), request.apiKey(), request.baseUrl());
            List<String> modelNames =
                    models.stream().map(ModelEntryDto::modelName).toList();
            return TestConnectionResponse.success(modelNames);
        } catch (Exception e) {
            return TestConnectionResponse.failure(e.getMessage());
        }
    }
}
