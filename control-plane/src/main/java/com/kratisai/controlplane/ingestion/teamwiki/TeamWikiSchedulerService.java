package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService.SnapshotDiff;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService.SnapshotState;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamWikiSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(TeamWikiSchedulerService.class);
    private static final int MIN_INGESTED_REPOS = 2;
    private static final List<IngestionStatus> ACTIVE_WORK_STATUSES =
            List.of(IngestionStatus.QUEUED, IngestionStatus.PROCESSING);

    private final TeamRepository teamRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final TeamWikiSnapshotService snapshotService;
    private final TeamWikiWorker teamWikiWorker;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final ApplicationEventPublisher eventPublisher;
    private final IngestionProperties.TeamWiki config;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<UUID, ReentrantLock> teamLocks = new ConcurrentHashMap<>();

    public TeamWikiSchedulerService(
            TeamRepository teamRepository,
            TeamWikiBatchRepository teamWikiBatchRepository,
            IngestionBatchRepository ingestionBatchRepository,
            TeamWikiSnapshotService snapshotService,
            TeamWikiWorker teamWikiWorker,
            LiteLLMProvisioningService litellmProvisioningService,
            ApplicationEventPublisher eventPublisher,
            IngestionProperties ingestionProperties,
            PlatformTransactionManager transactionManager) {
        this.teamRepository = teamRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.snapshotService = snapshotService;
        this.teamWikiWorker = teamWikiWorker;
        this.litellmProvisioningService = litellmProvisioningService;
        this.eventPublisher = eventPublisher;
        this.config = ingestionProperties.getTeamWiki();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${kratis.ingestion.team-wiki.poll-interval:30s}")
    public void evaluateAllTeams() {
        if (!config.isEnabled()) {
            return;
        }
        List<UUID> teamIds = teamRepository.findAll().stream().map(Team::getId).toList();
        for (UUID teamId : teamIds) {
            try {
                evaluateTeam(teamId);
            } catch (Exception e) {
                logger.warn("Team wiki evaluation failed for team {}: {}", teamId, e.getMessage(), e);
            }
        }
    }

    public @Nullable UUID evaluateTeam(UUID teamId) {
        ReentrantLock lock = teamLocks.computeIfAbsent(teamId, unused -> new ReentrantLock());
        lock.lock();
        try {
            return transactionTemplate.execute(status -> evaluateTeamLocked(teamId));
        } finally {
            lock.unlock();
        }
    }

    private @Nullable UUID evaluateTeamLocked(UUID teamId) {
        Instant now = Instant.now();
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return null;
        }

        SnapshotState currentState = snapshotService.captureCurrentState(teamId);
        int ingestedRepoCount = currentState.snapshot().repos().size();

        if (ingestedRepoCount < MIN_INGESTED_REPOS) {
            int deactivated = teamWikiBatchRepository.deactivateAllForTeam(teamId);
            if (deactivated > 0) {
                eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.TEAM_WIKI));
            }
            return null;
        }

        if (teamWikiBatchRepository.existsByTeamIdAndStatusIn(teamId, ACTIVE_WORK_STATUSES)) {
            return null;
        }
        if (!hasRequiredModels(team)) {
            return null;
        }

        TeamWikiBatch activeBatch =
                teamWikiBatchRepository.findByTeamIdAndIsActiveTrue(teamId).orElse(null);
        SnapshotDiff diff =
                snapshotService.diff(activeBatch != null ? activeBatch.getSourceSnapshot() : null, currentState);
        if (activeBatch != null && !diff.hasStructuralChange()) {
            return null;
        }

        TeamWikiBatch latestBatch = teamWikiBatchRepository
                .findFirstByTeamIdOrderByStartedAtDesc(teamId)
                .orElse(null);
        if (isInFailureBackoff(latestBatch, currentState, now)) {
            return null;
        }

        Instant dirtySince = resolveDirtySince(teamId, diff, now);
        if (isDeferredByInFlightRepos(teamId, dirtySince, now)) {
            return null;
        }

        if (resolveQuotaWaitUntil(teamId, now) != null) {
            return null;
        }

        TeamWikiBuildMode mode = activeBatch == null ? TeamWikiBuildMode.FULL : TeamWikiBuildMode.INCREMENTAL;
        return enqueueBuild(team, mode, TeamWikiTriggerKind.AUTOMATIC, currentState);
    }

    public TeamWikiBatch triggerManualBuild(UUID teamId, @Nullable TeamWikiBuildMode requestedMode) {
        ReentrantLock lock = teamLocks.computeIfAbsent(teamId, unused -> new ReentrantLock());
        lock.lock();
        try {
            return transactionTemplate.execute(status -> {
                Team team = teamRepository
                        .findById(teamId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
                SnapshotState currentState = snapshotService.captureCurrentState(teamId);
                if (currentState.snapshot().repos().size() < MIN_INGESTED_REPOS) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "At least 2 ingested repositories are required to build a team wiki.");
                }
                if (teamWikiBatchRepository.existsByTeamIdAndStatusIn(teamId, ACTIVE_WORK_STATUSES)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "A team wiki build is already queued or in progress.");
                }
                if (!hasRequiredModels(team)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Ingestion and embedding models must be configured for this team.");
                }
                litellmProvisioningService.ensureModelsRegistered(
                        team.getIngestionProvider(),
                        team.getIngestionModel(),
                        team.getEmbeddingProvider(),
                        team.getEmbeddingModel());

                boolean hasActive = teamWikiBatchRepository
                        .findByTeamIdAndIsActiveTrue(teamId)
                        .isPresent();
                TeamWikiBuildMode mode = !hasActive || requestedMode == null
                        ? (hasActive ? TeamWikiBuildMode.INCREMENTAL : TeamWikiBuildMode.FULL)
                        : requestedMode;

                UUID batchId = enqueueBuild(team, mode, TeamWikiTriggerKind.MANUAL, currentState);
                return teamWikiBatchRepository.findById(batchId).orElseThrow();
            });
        } finally {
            lock.unlock();
        }
    }

    public TeamWikiScheduleStatus computeStatus(UUID teamId) {
        Instant now = Instant.now();
        SnapshotState currentState = snapshotService.captureCurrentState(teamId);
        int ingestedCount = currentState.snapshot().repos().size();
        TeamWikiBatch activeBatch =
                teamWikiBatchRepository.findByTeamIdAndIsActiveTrue(teamId).orElse(null);
        List<TeamWikiBatch> recentBatches = teamWikiBatchRepository.findByTeamIdOrderByStartedAtDesc(teamId);
        TeamWikiBatch latestBatch = recentBatches.isEmpty() ? null : recentBatches.getFirst();

        if (ingestedCount < MIN_INGESTED_REPOS) {
            return new TeamWikiScheduleStatus(
                    ingestedCount, false, 0, DeferralReason.NONE, null, activeBatch, latestBatch, recentBatches);
        }

        SnapshotDiff diff =
                snapshotService.diff(activeBatch != null ? activeBatch.getSourceSnapshot() : null, currentState);
        boolean dirty = activeBatch == null || diff.hasStructuralChange();
        int nonStructuralCount = diff.reingestedRepos().size();

        DeferralReason reason = DeferralReason.NONE;
        Instant nextEligibleAt = null;
        if (dirty && !teamWikiBatchRepository.existsByTeamIdAndStatusIn(teamId, ACTIVE_WORK_STATUSES)) {
            if (isInFailureBackoff(latestBatch, currentState, now)) {
                reason = DeferralReason.FAILURE_BACKOFF;
                nextEligibleAt = latestBatch.getCompletedAt().plus(config.getFailureBackoff());
            } else {
                Instant dirtySince = resolveDirtySince(teamId, diff, now);
                if (isDeferredByInFlightRepos(teamId, dirtySince, now)) {
                    reason = DeferralReason.IN_FLIGHT_REPOS;
                    nextEligibleAt = dirtySince.plus(config.getMaxDeferral());
                } else {
                    Instant quotaUntil = resolveQuotaWaitUntil(teamId, now);
                    if (quotaUntil != null) {
                        reason = DeferralReason.QUOTA_REACHED;
                        nextEligibleAt = quotaUntil;
                    }
                }
            }
        }

        return new TeamWikiScheduleStatus(
                ingestedCount,
                dirty,
                nonStructuralCount,
                reason,
                nextEligibleAt,
                activeBatch,
                latestBatch,
                recentBatches);
    }

    private UUID enqueueBuild(
            Team team, TeamWikiBuildMode mode, TeamWikiTriggerKind triggerKind, SnapshotState currentState) {
        litellmProvisioningService.ensureModelsRegistered(
                team.getIngestionProvider(),
                team.getIngestionModel(),
                team.getEmbeddingProvider(),
                team.getEmbeddingModel());

        TeamWikiBatch batch = new TeamWikiBatch(team, mode, triggerKind, currentState.snapshot());
        teamWikiBatchRepository.saveAndFlush(batch);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(team.getId(), TeamEntityType.TEAM_WIKI));

        UUID batchId = batch.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                teamWikiWorker.runBuild(batchId);
            }
        });
        logger.info("Queued {} {} team wiki batch {} for team {}", triggerKind, mode, batchId, team.getName());
        return batchId;
    }

    private boolean isInFailureBackoff(@Nullable TeamWikiBatch latestBatch, SnapshotState currentState, Instant now) {
        if (latestBatch == null || latestBatch.getStatus() != IngestionStatus.FAILED) {
            return false;
        }
        Instant failedAt = latestBatch.getCompletedAt();
        if (failedAt == null || !now.isBefore(failedAt.plus(config.getFailureBackoff()))) {
            return false;
        }
        boolean hasNewerRepoCompletion = currentState.batchCompletedAtByRepo().values().stream()
                .anyMatch(ts -> ts != null && ts.isAfter(failedAt));
        boolean hasNewerRelation =
                currentState.relationCreatedAt().values().stream().anyMatch(ts -> ts != null && ts.isAfter(failedAt));
        return !hasNewerRepoCompletion && !hasNewerRelation;
    }

    private Instant resolveDirtySince(UUID teamId, SnapshotDiff diff, Instant now) {
        if (diff.earliestStructuralChangeAt() != null) {
            return diff.earliestStructuralChangeAt();
        }
        return ingestionBatchRepository.findByRepositoryTeamIdAndStatusIn(teamId, ACTIVE_WORK_STATUSES).stream()
                .map(IngestionBatch::getStartedAt)
                .min(Comparator.naturalOrder())
                .orElse(now);
    }

    private boolean isDeferredByInFlightRepos(UUID teamId, Instant dirtySince, Instant now) {
        if (!ingestionBatchRepository.existsByRepositoryTeamIdAndStatusIn(teamId, ACTIVE_WORK_STATUSES)) {
            return false;
        }
        return now.isBefore(dirtySince.plus(config.getMaxDeferral()));
    }

    private @Nullable Instant resolveQuotaWaitUntil(UUID teamId, Instant now) {
        Instant windowStart = now.minus(Duration.ofHours(24));
        List<TeamWikiBatch> recentAuto =
                teamWikiBatchRepository.findByTeamIdAndTriggerKindAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        teamId, TeamWikiTriggerKind.AUTOMATIC, windowStart);
        if (recentAuto.size() < config.getMaxBuildsPer24h()) {
            return null;
        }
        return recentAuto.getFirst().getStartedAt().plus(Duration.ofHours(24));
    }

    private static boolean hasRequiredModels(Team team) {
        return team.getIngestionProvider() != null
                && team.getIngestionModel() != null
                && !team.getIngestionModel().isBlank()
                && team.getEmbeddingProvider() != null
                && team.getEmbeddingModel() != null
                && !team.getEmbeddingModel().isBlank();
    }

    public enum DeferralReason {
        NONE,
        IN_FLIGHT_REPOS,
        QUOTA_REACHED,
        FAILURE_BACKOFF
    }

    public record TeamWikiScheduleStatus(
            int ingestedRepoCount,
            boolean dirty,
            int nonStructuralChangedRepoCount,
            DeferralReason deferralReason,
            @Nullable Instant nextEligibleAt,
            @Nullable TeamWikiBatch activeBatch,
            @Nullable TeamWikiBatch latestBatch,
            List<TeamWikiBatch> recentBatches) {}
}
