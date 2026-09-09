package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.CreateEnvironmentProviderRequest;
import com.kratisai.controlplane.api.restdto.EnvironmentProviderDto;
import com.kratisai.controlplane.api.restdto.UpdateEnvironmentProviderRequest;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnvironmentProviderService {

    private final EnvironmentProviderRepository environmentProviderRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;

    public EnvironmentProviderService(
            EnvironmentProviderRepository environmentProviderRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository) {
        this.environmentProviderRepository = environmentProviderRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    public EnvironmentProviderDto toDto(EnvironmentProvider provider) {
        return new EnvironmentProviderDto(
                provider.getId(), provider.getTeam().getId(), provider.getName(), provider.getDockerImage());
    }

    public List<EnvironmentProviderDto> listProviders(UUID userId, UUID teamId) {
        requireTeamMembership(userId, teamId);
        return environmentProviderRepository.findByTeamId(teamId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public EnvironmentProviderDto createProvider(UUID userId, UUID teamId, CreateEnvironmentProviderRequest request) {
        requireTeamMembership(userId, teamId);

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        EnvironmentProvider provider = new EnvironmentProvider();
        provider.setTeam(team);
        provider.setName(request.name());
        provider.setDockerImage(request.dockerImage());

        environmentProviderRepository.save(provider);
        return toDto(provider);
    }

    @Transactional
    public EnvironmentProviderDto updateProvider(
            UUID userId, UUID teamId, UUID providerId, UpdateEnvironmentProviderRequest request) {
        requireTeamMembership(userId, teamId);

        EnvironmentProvider provider = environmentProviderRepository
                .findByTeamIdAndId(teamId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));

        if (request.name() != null) {
            provider.setName(request.name());
        }
        if (request.dockerImage() != null) {
            provider.setDockerImage(request.dockerImage());
        }

        environmentProviderRepository.save(provider);
        return toDto(provider);
    }

    @Transactional
    public void deleteProvider(UUID userId, UUID teamId, UUID providerId) {
        requireTeamMembership(userId, teamId);

        EnvironmentProvider provider = environmentProviderRepository
                .findByTeamIdAndId(teamId, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));

        environmentProviderRepository.delete(provider);
    }
}
