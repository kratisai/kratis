package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        value = "kratis.sandbox.lifecycle.retention.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SnapshotRetentionService {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotRetentionService.class);

    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxOrchestratorService sandboxOrchestratorService;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration retentionPeriod;

    public SnapshotRetentionService(
            ExecutionEnvironmentRepository environmentRepository,
            SandboxOrchestratorService sandboxOrchestratorService,
            ApplicationEventPublisher eventPublisher,
            SandboxProperties sandboxProperties) {
        this.environmentRepository = environmentRepository;
        this.sandboxOrchestratorService = sandboxOrchestratorService;
        this.eventPublisher = eventPublisher;
        this.retentionPeriod = sandboxProperties.getLifecycle().getRetention().getPeriod();
    }

    @Scheduled(fixedRateString = "${kratis.sandbox.lifecycle.retention.poll-interval:1d}")
    @Transactional
    public void expireStaleSnapshots() {
        Instant cutoff = Instant.now().minus(retentionPeriod);
        List<ExecutionEnvironment> expired = environmentRepository.findExpiredSleeping(cutoff);
        for (ExecutionEnvironment env : expired) {
            UUID envId = env.getId();
            logger.info(
                    "Suspend snapshot for environment {} is unused for more than {} day(s); terminating",
                    envId,
                    retentionPeriod.toDays());
            try {
                terminateStale(env);
            } catch (Exception e) {
                logger.error("Failed to expire suspend snapshot for environment {}", envId, e);
            }
        }
    }

    private void terminateStale(ExecutionEnvironment env) {
        if (env.getProvider() != null) {
            try {
                SandboxProvider provider =
                        sandboxOrchestratorService.getProvider(env.getProvider().getType());
                provider.destroy(env.getId().toString());
            } catch (Exception e) {
                logger.warn("Failed to destroy expired environment {}: {}", env.getId(), e.getMessage());
            }
        }
        env.setStatus(EnvironmentStatus.TERMINATED);
        environmentRepository.save(env);
        if (env.getTeam() != null) {
            eventPublisher.publishEvent(new TeamEntityChangedEvent(env.getTeam().getId(), TeamEntityType.ENVIRONMENTS));
        }
    }
}
