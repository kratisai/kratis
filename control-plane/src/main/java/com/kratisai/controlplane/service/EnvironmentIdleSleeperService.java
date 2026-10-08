package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-suspends CONNECTED sandbox environments once the latest execution has been idle for
 * {@code kratis.sandbox.lifecycle.sleep.idle-timeout}. Only environments with at least one execution
 * are tracked, so freshly provisioned sandboxes stay awake until first use.
 */
@Service
@ConditionalOnProperty(value = "kratis.sandbox.lifecycle.sleep.enabled", havingValue = "true", matchIfMissing = true)
public class EnvironmentIdleSleeperService {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentIdleSleeperService.class);

    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final Duration idleThreshold;

    // Follows ZombieContainerCollector: in-memory first-seen tracking; a control-plane restart
    // simply restarts the idle countdown.
    private final Map<UUID, Instant> idleFirstSeen = new HashMap<>();

    public EnvironmentIdleSleeperService(
            ExecutionEnvironmentRepository environmentRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            ExecutionEnvironmentService executionEnvironmentService,
            SandboxProperties sandboxProperties) {
        this.environmentRepository = environmentRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.executionEnvironmentService = executionEnvironmentService;
        this.idleThreshold = sandboxProperties.getLifecycle().getSleep().getIdleTimeout();
    }

    @Scheduled(fixedRateString = "${kratis.sandbox.lifecycle.sleep.poll-interval:60s}")
    @Transactional
    public void sleepIdleEnvironments() {
        Instant now = Instant.now();
        List<ExecutionEnvironment> connected = environmentRepository.findByStatus(EnvironmentStatus.CONNECTED);
        for (ExecutionEnvironment env : connected) {
            if (env.getType() != ExecutionEnvironmentType.SANDBOX) {
                continue;
            }
            UUID envId = env.getId();
            List<SandboxExecutionStatus> runningStatuses = List.of(SandboxExecutionStatus.RUNNING);
            if (!sandboxExecutionRepository
                    .findByEnvironmentIdAndStatusIn(envId, runningStatuses)
                    .isEmpty()) {
                idleFirstSeen.remove(envId);
                continue;
            }
            if (!hasAnyExecution(envId)) {
                continue;
            }
            Instant firstSeen = idleFirstSeen.computeIfAbsent(envId, k -> now);
            long idleSeconds = Duration.between(firstSeen, now).getSeconds();
            if (idleSeconds < idleThreshold.toSeconds()) {
                continue;
            }
            idleFirstSeen.remove(envId);
            if (!executionEnvironmentService.isContainerRunning(env)) {
                logger.info(
                        "Environment {} idle for {}s but its container is not running; marking disconnected",
                        envId,
                        idleSeconds);
                executionEnvironmentService.handleStoppedContainer(env);
                continue;
            }
            logger.info("Environment {} idle for {}s; suspending", envId, idleSeconds);
            try {
                executionEnvironmentService.sleepEnvironmentInternal(env);
            } catch (Exception e) {
                logger.error("Failed to auto-suspend idle environment {}", envId, e);
            }
        }
    }

    private boolean hasAnyExecution(UUID envId) {
        return sandboxExecutionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(envId)
                .isPresent();
    }
}
