package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.event.UserEntityChangedEvent;
import com.kratisai.controlplane.model.event.UserEntityType;
import com.kratisai.controlplane.repository.*;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EnvironmentProviderRepository environmentProviderRepository;
    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final LiteLLMProvisioningService liteLLMProvisioningService;
    private final String runnerImage;

    public TeamService(
            TeamRepository teamRepository,
            TeamMemberRepository teamMemberRepository,
            UserRepository userRepository,
            ModelProviderRepository modelProviderRepository,
            ApplicationEventPublisher eventPublisher,
            EnvironmentProviderRepository environmentProviderRepository,
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            LiteLLMProvisioningService liteLLMProvisioningService,
            @Value("${kratis.sandbox.runner-image}") String runnerImage) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.eventPublisher = eventPublisher;
        this.environmentProviderRepository = environmentProviderRepository;
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.liteLLMProvisioningService = liteLLMProvisioningService;
        this.runnerImage = runnerImage;
    }

    @Transactional
    public TeamDto createTeam(UUID userId, CreateTeamRequest request) {
        return createTeam(userId, request, false);
    }

    @Transactional
    public TeamDto createTeam(UUID userId, CreateTeamRequest request, boolean isDefault) {
        Team team = new Team(request.name(), request.description(), isDefault);
        teamRepository.save(team);

        TeamMember owner = new TeamMember(userRepository.getReferenceById(userId), team, "owner");
        teamMemberRepository.save(owner);

        // Create default environment provider for the new team
        EnvironmentProvider defaultProvider = new EnvironmentProvider();
        defaultProvider.setTeam(team);
        defaultProvider.setName("Default Docker Provider");
        defaultProvider.setDockerImage(runnerImage);
        environmentProviderRepository.save(defaultProvider);

        // Create default execution environment for the new team
        ExecutionEnvironment defaultEnv = new ExecutionEnvironment();
        defaultEnv.setTeam(team);
        defaultEnv.setName("Default Sandbox");
        defaultEnv.setType(ExecutionEnvironmentType.SANDBOX);
        defaultEnv.setContainerId(runnerImage);
        defaultEnv.setStatus(EnvironmentStatus.DISCONNECTED);
        executionEnvironmentRepository.save(defaultEnv);

        eventPublisher.publishEvent(new UserEntityChangedEvent(userId, UserEntityType.TEAMS));

        return new TeamDto(
                team.getId(),
                team.getName(),
                team.getDescription(),
                "owner",
                team.isDefault(),
                team.getTavilyApiKey() != null && !team.getTavilyApiKey().isEmpty(),
                team.getIngestionProvider() != null
                        ? team.getIngestionProvider().getId()
                        : null,
                team.getIngestionModel(),
                team.getEmbeddingProvider() != null
                        ? team.getEmbeddingProvider().getId()
                        : null,
                team.getEmbeddingModel(),
                team.getCreatedAt());
    }

    @Transactional
    public TeamDto createDefaultTeam(UUID userId) {
        User user = userRepository.getReferenceById(userId);
        String teamName = user.getDisplayName() + "'s Team";
        CreateTeamRequest request = new CreateTeamRequest(teamName, "Default team for " + user.getDisplayName());
        return createTeam(userId, request, true);
    }

    public List<TeamDto> listTeamsForUser(UUID userId) {
        // Deterministic order (default team first, then creation time) so the API
        // response and its consumers don't depend on unordered repository results.
        List<TeamMember> members = teamMemberRepository.findByUserId(userId);
        members.sort(Comparator.comparing((TeamMember tm) -> tm.getTeam().isDefault())
                .reversed()
                .thenComparing(tm -> tm.getTeam().getCreatedAt())
                .thenComparing(tm -> tm.getTeam().getId()));
        return members.stream()
                .map(tm -> new TeamDto(
                        tm.getTeam().getId(),
                        tm.getTeam().getName(),
                        tm.getTeam().getDescription(),
                        tm.getRole(),
                        tm.getTeam().isDefault(),
                        tm.getTeam().getTavilyApiKey() != null
                                && !tm.getTeam().getTavilyApiKey().isEmpty(),
                        tm.getTeam().getIngestionProvider() != null
                                ? tm.getTeam().getIngestionProvider().getId()
                                : null,
                        tm.getTeam().getIngestionModel(),
                        tm.getTeam().getEmbeddingProvider() != null
                                ? tm.getTeam().getEmbeddingProvider().getId()
                                : null,
                        tm.getTeam().getEmbeddingModel(),
                        tm.getTeam().getCreatedAt()))
                .toList();
    }

    public TeamDetailDto getTeam(UUID userId, UUID teamId) {
        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        String role = teamMemberRepository
                .findByTeamIdAndUserId(teamId, userId)
                .map(TeamMember::getRole)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"));

        List<TeamMemberDto> members = teamMemberRepository.findByTeamId(teamId).stream()
                .map(tm -> new TeamMemberDto(
                        tm.getUser().getId(),
                        tm.getUser().getEmail(),
                        tm.getUser().getDisplayName(),
                        tm.getRole()))
                .toList();

        return new TeamDetailDto(
                team.getId(),
                team.getName(),
                team.getDescription(),
                role,
                team.isDefault(),
                team.getTavilyApiKey() != null && !team.getTavilyApiKey().isEmpty(),
                team.getIngestionProvider() != null
                        ? team.getIngestionProvider().getId()
                        : null,
                team.getIngestionModel(),
                team.getEmbeddingProvider() != null
                        ? team.getEmbeddingProvider().getId()
                        : null,
                team.getEmbeddingModel(),
                members,
                team.getCreatedAt(),
                team.getUpdatedAt());
    }

    @Transactional
    public TeamDto updateTeam(UUID userId, UUID teamId, UpdateTeamRequest request) {
        requireRole(userId, teamId, "owner", "admin");

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        if (request.name() != null) {
            team.setName(request.name());
        }
        if (request.description() != null) {
            team.setDescription(request.description());
        }
        if (request.tavilyApiKey() != null) {
            team.setTavilyApiKey(request.tavilyApiKey());
        }
        if (request.ingestionProvider() != null) {
            ModelProvider provider = modelProviderRepository
                    .findById(request.ingestionProvider())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));
            team.setIngestionProvider(provider);
        }
        if (request.ingestionModel() != null) {
            team.setIngestionModel(request.ingestionModel());
        }
        if (request.embeddingProvider() != null) {
            ModelProvider provider = modelProviderRepository
                    .findById(request.embeddingProvider())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found"));
            team.setEmbeddingProvider(provider);
        }
        if (request.embeddingModel() != null) {
            team.setEmbeddingModel(request.embeddingModel());
        }

        if (request.ingestionProvider() != null || request.ingestionModel() != null) {
            ensureModelProvisioned(team.getIngestionProvider(), team.getIngestionModel(), ModelKind.CHAT);
        }
        if (request.embeddingProvider() != null || request.embeddingModel() != null) {
            ensureModelProvisioned(team.getEmbeddingProvider(), team.getEmbeddingModel(), ModelKind.EMBEDDING);
        }

        teamRepository.save(team);

        List<TeamMember> currentMembers = teamMemberRepository.findByTeamId(teamId);
        for (TeamMember member : currentMembers) {
            eventPublisher.publishEvent(
                    new UserEntityChangedEvent(member.getUser().getId(), UserEntityType.TEAMS));
        }

        String role = teamMemberRepository
                .findByTeamIdAndUserId(teamId, userId)
                .map(TeamMember::getRole)
                .orElse("member");

        return new TeamDto(
                team.getId(),
                team.getName(),
                team.getDescription(),
                role,
                team.isDefault(),
                team.getTavilyApiKey() != null && !team.getTavilyApiKey().isEmpty(),
                team.getIngestionProvider() != null
                        ? team.getIngestionProvider().getId()
                        : null,
                team.getIngestionModel(),
                team.getEmbeddingProvider() != null
                        ? team.getEmbeddingProvider().getId()
                        : null,
                team.getEmbeddingModel(),
                team.getCreatedAt());
    }

    @Transactional
    public void deleteTeam(UUID userId, UUID teamId) {
        requireRole(userId, teamId, "owner");

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        if (team.isDefault()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot delete your default team. A user must have at least one default team.");
        }

        List<TeamMember> currentMembers = teamMemberRepository.findByTeamId(teamId);
        teamRepository.deleteById(teamId);

        for (TeamMember member : currentMembers) {
            eventPublisher.publishEvent(
                    new UserEntityChangedEvent(member.getUser().getId(), UserEntityType.TEAMS));
        }
    }

    @Transactional
    public TeamMemberDto addMember(UUID userId, UUID teamId, AddTeamMemberRequest request) {
        requireRole(userId, teamId, "owner", "admin");

        User user = userRepository
                .findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member");
        }

        TeamMember member = new TeamMember(user, team, request.role());
        teamMemberRepository.save(member);

        eventPublisher.publishEvent(new UserEntityChangedEvent(user.getId(), UserEntityType.TEAMS));

        return new TeamMemberDto(user.getId(), user.getEmail(), user.getDisplayName(), request.role());
    }

    @Transactional
    public void removeMember(UUID userId, UUID teamId, UUID targetUserId) {
        requireRole(userId, teamId, "owner", "admin");
        teamMemberRepository.deleteByTeamIdAndUserId(teamId, targetUserId);
        eventPublisher.publishEvent(new UserEntityChangedEvent(targetUserId, UserEntityType.TEAMS));
    }

    public void validateMembership(UUID userId, UUID teamId) {
        teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        teamMemberRepository
                .findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"));
    }

    private void ensureModelProvisioned(ModelProvider provider, String modelName, ModelKind kind) {
        liteLLMProvisioningService.ensureModelRegistered(provider, modelName, kind);
    }

    private void requireRole(UUID userId, UUID teamId, String... requiredRoles) {
        String role = teamMemberRepository
                .findByTeamIdAndUserId(teamId, userId)
                .map(TeamMember::getRole)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"));

        for (String required : requiredRoles) {
            if (required.equals(role)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions");
    }

    public String getTavilyApiKey(UUID teamId) {
        return teamRepository.findById(teamId).map(Team::getTavilyApiKey).orElse(null);
    }

    /**
     * Update the Tavily API key for a team.
     *
     * @param userId the user ID
     * @param teamId the team ID
     * @param tavilyApiKey the new Tavily API key (can be null to clear)
     */
    @Transactional
    public void updateTavilyApiKey(UUID userId, UUID teamId, String tavilyApiKey) {
        requireRole(userId, teamId, "owner", "admin");

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        team.setTavilyApiKey(tavilyApiKey);
        teamRepository.save(team);

        List<TeamMember> currentMembers = teamMemberRepository.findByTeamId(teamId);
        for (TeamMember member : currentMembers) {
            eventPublisher.publishEvent(
                    new UserEntityChangedEvent(member.getUser().getId(), UserEntityType.TEAMS));
        }
    }
}
