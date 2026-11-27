package com.kratisai.controlplane.service;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.repository.ChangeSetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the graph needed to query a provider and persists the observed status in short, separate
 * transactions so one failing pull request cannot abort the rest of a polling batch.
 */
@Service
public class PrStatusRefresher {

    private final ChangeSetRepository changeSetRepository;
    private final GitCredentialResolver credentialResolver;
    private final RepoProviderRegistry providerRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public PrStatusRefresher(
            ChangeSetRepository changeSetRepository,
            GitCredentialResolver credentialResolver,
            RepoProviderRegistry providerRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.changeSetRepository = Objects.requireNonNull(changeSetRepository);
        this.credentialResolver = Objects.requireNonNull(credentialResolver);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Transactional(readOnly = true)
    public List<UUID> findCandidateIds(Instant cutoff) {
        return changeSetRepository.findRecentOpenPullRequestIds(PublishedPrStatus.OPEN, cutoff);
    }

    @Transactional(readOnly = true)
    public Optional<PollTarget> load(UUID changeSetId) {
        return changeSetRepository.findWithPollingGraphById(changeSetId).flatMap(changeSet -> {
            Repository repository = changeSet.getExecution().getRepository();
            if (repository == null || changeSet.getPrNumber() == null) {
                return Optional.empty();
            }
            return Optional.of(new PollTarget(
                    changeSet.getPrNumber(),
                    changeSet.getPrStatus(),
                    repository,
                    providerRegistry.getProvider(repository),
                    credentialResolver.resolve(repository)));
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(UUID changeSetId, PublishedPrStatus observedStatus) {
        ChangeSet changeSet =
                changeSetRepository.findWithPollingGraphById(changeSetId).orElse(null);
        if (changeSet == null || changeSet.getPrStatus() == observedStatus) {
            return;
        }
        changeSet.setPrStatus(observedStatus);
        changeSet.getExecution().getChat().touchUpdatedAt(Instant.now());
        changeSetRepository.save(changeSet);
        if (observedStatus == PublishedPrStatus.MERGED || observedStatus == PublishedPrStatus.CLOSED) {
            var execution = changeSet.getExecution();
            var chat = execution.getChat();
            eventPublisher.publishEvent(
                    new ExecutionStatusChangedEvent(chat.getTeam().getId(), chat.getId(), execution.getId()));
        }
    }

    public record PollTarget(
            long prNumber,
            PublishedPrStatus currentStatus,
            Repository repository,
            RepoProvider provider,
            GitAuthMaterial auth) {

        public PollTarget {
            Objects.requireNonNull(repository);
            Objects.requireNonNull(provider);
            Objects.requireNonNull(auth);
        }
    }
}
