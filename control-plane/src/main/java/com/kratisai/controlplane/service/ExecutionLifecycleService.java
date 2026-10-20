package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sweeps executions independently of environments: auto-transitions RUNNING executions
 * to IDLE when inactive for activity-timeout.
 */
@Service
@ConditionalOnProperty(value = "kratis.sandbox.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionLifecycleService.class);

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final Duration activityTimeout;
    private final Duration settleWindow;
    private final Instant bootInstant = Instant.now();

    public ExecutionLifecycleService(
            SandboxExecutionRepository sandboxExecutionRepository,
            ExecutionActivityPersistenceService activityPersistenceService,
            ApplicationEventPublisher eventPublisher,
            PendingHitlRegistry pendingHitlRegistry,
            SandboxProperties sandboxProperties) {
        this.sandboxExecutionRepository = Objects.requireNonNull(sandboxExecutionRepository);
        this.activityPersistenceService = Objects.requireNonNull(activityPersistenceService);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.pendingHitlRegistry = Objects.requireNonNull(pendingHitlRegistry);
        this.activityTimeout = sandboxProperties.getLifecycle().getActivityTimeout();
        this.settleWindow = sandboxProperties.getLifecycle().getSettleWindow();
    }

    @Scheduled(fixedRateString = "${kratis.sandbox.lifecycle.poll-interval:30s}")
    @Transactional
    public void sweep() {
        Duration sinceBoot = Duration.between(bootInstant, Instant.now());
        if (sinceBoot.compareTo(settleWindow) < 0) {
            logger.debug(
                    "Execution sweep paused for settle window ({}s remaining)",
                    settleWindow.minus(sinceBoot).toSeconds());
            return;
        }
        settleInactiveExecutions(Instant.now());
    }

    public boolean hasRunningExecutions(UUID environmentId) {
        if (environmentId == null) {
            return false;
        }
        return sandboxExecutionRepository.existsByEnvironmentIdAndStatus(environmentId, SandboxExecutionStatus.RUNNING);
    }

    public boolean hasPendingHitl(UUID environmentId) {
        if (environmentId == null) {
            return false;
        }
        return sandboxExecutionRepository
                .findByEnvironmentIdAndStatusIn(
                        environmentId, List.of(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE))
                .stream()
                .anyMatch(execution -> pendingHitlRegistry.hasPendingForExecution(execution.getId()));
    }

    private void settleInactiveExecutions(Instant now) {
        Instant cutoff = now.minus(activityTimeout);
        for (Object[] row : sandboxExecutionRepository.findRunningExecutionsWithLastActivity()) {
            SandboxExecution execution = (SandboxExecution) row[0];
            Instant lastActivity = (Instant) row[1];
            if (lastActivity == null || lastActivity.isAfter(cutoff)) {
                continue;
            }
            if (pendingHitlRegistry.hasPendingForExecution(execution.getId())) {
                logger.trace("Execution {} awaits a human answer; keeping RUNNING", execution.getId());
                continue;
            }
            logger.debug(
                    "Execution {} inactive since {} (> {}m); transitioning to IDLE",
                    execution.getId(),
                    lastActivity,
                    activityTimeout.toMinutes());
            markExecutionIdle(execution);
        }
    }

    private void markExecutionIdle(SandboxExecution execution) {
        execution.setStatus(SandboxExecutionStatus.IDLE);
        sandboxExecutionRepository.save(execution);

        activityPersistenceService.recordActivity(
                execution.getId(),
                ActivityType.MESSAGE,
                "Execution quiet for " + activityTimeout.toMinutes() + "m; status set to IDLE",
                null,
                ActivityStatus.COMPLETED,
                null);

        UUID teamId = execution.getChat().getTeam().getId();
        eventPublisher.publishEvent(
                new ExecutionStatusChangedEvent(teamId, execution.getChat().getId(), execution.getId()));
    }
}
