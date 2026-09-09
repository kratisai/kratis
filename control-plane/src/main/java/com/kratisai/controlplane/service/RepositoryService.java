package com.kratisai.controlplane.service;

import static java.util.Objects.requireNonNull;

import com.kratisai.controlplane.api.restdto.RegisterRepositoryRequest;
import com.kratisai.controlplane.api.restdto.RepositoryDto;
import com.kratisai.controlplane.api.restdto.UpdateRepositoryRequest;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RepositoryService {

    private final RepositoryRepository repositoryRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final RepoCredentialRepository repoCredentialRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RepositoryService(
            RepositoryRepository repositoryRepository,
            TeamMemberRepository teamMemberRepository,
            TeamRepository teamRepository,
            RepoCredentialRepository repoCredentialRepository,
            IngestionBatchRepository ingestionBatchRepository,
            ApplicationEventPublisher eventPublisher) {
        this.repositoryRepository = repositoryRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.repoCredentialRepository = repoCredentialRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.eventPublisher = eventPublisher;
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    public RepositoryDto toDto(Repository repo) {
        Optional<IngestionBatch> latestBatch =
                ingestionBatchRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repo.getId());

        Integer queuePosition = null;
        if (latestBatch.isPresent() && latestBatch.get().getStatus() == IngestionStatus.QUEUED) {
            IngestionBatch batch = latestBatch.get();
            int positionBefore = ingestionBatchRepository.countQueuedBatchesBefore(batch.getStartedAt(), batch.getId());
            queuePosition = positionBefore + 1;
        }

        return new RepositoryDto(
                repo.getId(),
                repo.getName(),
                repo.getUrl(),
                repo.getBranch(),
                repo.getRepositoryType(),
                repo.getTeam().getId(),
                repo.getCredential() != null ? repo.getCredential().getId() : null,
                repo.getCreatedAt(),
                repo.getUpdatedAt(),
                latestBatch.map(IngestionBatch::getStatus).orElse(null),
                latestBatch.map(IngestionBatch::getCompletedAt).orElse(null),
                latestBatch.map(IngestionBatch::getCommitHash).orElse(null),
                queuePosition);
    }

    @Transactional
    public RepositoryDto registerRepository(UUID userId, UUID teamId, RegisterRepositoryRequest request) {
        requireTeamMembership(userId, teamId);

        if (repositoryRepository.existsByTeamIdAndName(teamId, request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Repository name already exists in this team");
        }

        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        Repository repo = new Repository(
                request.name(),
                request.url(),
                request.branch() != null ? request.branch() : "main",
                requireNonNull(request.repositoryType(), "repositoryType is required for new repositories"));
        repo.setTeam(team);

        if (request.credentialId() != null) {
            RepoCredential credential = repoCredentialRepository
                    .findByTeamIdAndId(teamId, request.credentialId())
                    .orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found for this team"));
            repo.setCredential(credential);
        }

        repositoryRepository.save(repo);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.REPOSITORIES));
        return toDto(repo);
    }

    public List<RepositoryDto> listRepositories(UUID userId, UUID teamId) {
        requireTeamMembership(userId, teamId);
        return repositoryRepository.findByTeamId(teamId).stream()
                .map(this::toDto)
                .toList();
    }

    public RepositoryDto getRepository(UUID userId, UUID teamId, UUID repoId) {
        requireTeamMembership(userId, teamId);
        Repository repo = repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));
        return toDto(repo);
    }

    @Transactional
    public RepositoryDto updateRepository(UUID userId, UUID teamId, UUID repoId, UpdateRepositoryRequest request) {
        requireTeamMembership(userId, teamId);
        Repository repo = repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (request.name() != null) {
            repo.setName(request.name());
        }
        if (request.branch() != null) {
            repo.setBranch(request.branch());
        }
        if (request.repositoryType() != null) {
            repo.setRepositoryType(request.repositoryType());
        }
        if (request.credentialId() != null) {
            RepoCredential credential = repoCredentialRepository
                    .findByTeamIdAndId(teamId, request.credentialId())
                    .orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found for this team"));
            repo.setCredential(credential);
        }

        repositoryRepository.save(repo);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.REPOSITORIES));
        return toDto(repo);
    }

    @Transactional
    public void deleteRepository(UUID userId, UUID teamId, UUID repoId) {
        requireTeamMembership(userId, teamId);
        Repository repo = repositoryRepository
                .findByTeamIdAndId(teamId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));
        repositoryRepository.delete(repo);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.REPOSITORIES));
    }
}
