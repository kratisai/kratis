package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.ExecutionProviderType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Environment lifecycle sweep. The provider interface speaks only environment ids: any live
 * element (runner, dind, network) makes a row "active"; a retained snapshot makes it "retained".
 * Cleanup is keyed on the row, never on Docker element kinds.
 */
@Service
@ConditionalOnProperty(value = "kratis.sandbox.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class EnvironmentLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentLifecycleService.class);

    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final ExecutionLifecycleService executionLifecycleService;
    private final List<SandboxProvider> sandboxProviders;
    private final Duration idleTimeout;
    private final Duration startupGrace;
    private final Duration retentionPeriod;
    private final Duration connectTimeout;
    private final Duration settleWindow;
    private final Instant bootInstant = Instant.now();

    // Orphans have no row to carry a durable timestamp; their grace is the one remaining
    // in-memory clock. A lost entry only delays the purge by one more grace.
    private final Map<UUID, Instant> orphanFirstSeen = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> orphanSnapshotFirstSeen = new ConcurrentHashMap<>();

    public EnvironmentLifecycleService(
            ExecutionEnvironmentRepository environmentRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            ExecutionEnvironmentService executionEnvironmentService,
            ExecutionLifecycleService executionLifecycleService,
            List<SandboxProvider> sandboxProviders,
            SandboxProperties sandboxProperties) {
        this.environmentRepository = Objects.requireNonNull(environmentRepository);
        this.sandboxExecutionRepository = Objects.requireNonNull(sandboxExecutionRepository);
        this.executionEnvironmentService = Objects.requireNonNull(executionEnvironmentService);
        this.executionLifecycleService = Objects.requireNonNull(executionLifecycleService);
        this.sandboxProviders = Objects.requireNonNull(sandboxProviders);
        this.idleTimeout = sandboxProperties.getLifecycle().getIdleTimeout();
        this.startupGrace = sandboxProperties.getLifecycle().getStartupGrace();
        this.retentionPeriod = sandboxProperties.getLifecycle().getRetentionPeriod();
        this.connectTimeout = sandboxProperties.getLifecycle().getConnectTimeout();
        this.settleWindow = sandboxProperties.getLifecycle().getSettleWindow();
    }

    @Scheduled(fixedRateString = "${kratis.sandbox.lifecycle.poll-interval:30s}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        Duration sinceBoot = Duration.between(bootInstant, now);
        if (sinceBoot.compareTo(settleWindow) < 0) {
            logger.debug(
                    "Lifecycle sweep paused for settle window ({}s remaining)",
                    settleWindow.minus(sinceBoot).toSeconds());
            return;
        }
        resolveStuckResumes(now);
        suspendIdleSandboxes(now);
        expireStaleSnapshots(now);
        applyLostRuntimeRule(now);
        reapOrphans(now);
        failExecutionsOfTerminatedEnvironments();
    }

    /**
     * A PENDING_CONNECT row that never registered has a lost runtime. Resume already rolls back
     * inside its own transaction, so this handles rows whose spawn never completed: remove owned
     * compute, keep the snapshot when one exists by returning to SLEEPING, else TERMINATED.
     */
    private void resolveStuckResumes(Instant now) {
        for (ExecutionEnvironment env : environmentRepository.findByTypeAndStatus(
                ExecutionEnvironmentType.SANDBOX, EnvironmentStatus.PENDING_CONNECT)) {
            if (!elapsed(env.getStateChangedAt(), now, connectTimeout)) {
                continue;
            }
            SandboxProvider provider = providerFor(env);
            if (provider == null) {
                continue;
            }
            failRow(
                    "resolving stuck resume",
                    () -> {
                        if (provider.isSandboxRunning(env.getId())) {
                            logger.trace("Stuck-resume check: env {} runtime live; keeping", env.getId());
                            return;
                        }
                        logger.warn("Environment {} never connected; removing compute", env.getId());
                        provider.terminateSandbox(env.getId());
                        if (provider.isRetained(env.getId())) {
                            executionEnvironmentService.rollbackFailedResume(env);
                        } else {
                            executionEnvironmentService.reconcilePurgedEnvironment(env);
                        }
                    },
                    env.getId());
        }
    }

    private void suspendIdleSandboxes(Instant now) {
        for (ExecutionEnvironment env : environmentRepository.findByTypeAndStatus(
                ExecutionEnvironmentType.SANDBOX, EnvironmentStatus.CONNECTED)) {
            if (executionLifecycleService.hasRunningExecutions(env.getId())) {
                logger.trace("Idle check: env {} has a RUNNING execution; skipping", env.getId());
                continue;
            }
            if (executionLifecycleService.hasPendingHitl(env.getId())) {
                logger.trace("Idle check: env {} awaits a human answer; skipping", env.getId());
                continue;
            }
            if (!elapsed(env.getStateChangedAt(), now, idleTimeout)) {
                logger.trace("Idle check: env {} quiet since {}; not yet idle", env.getId(), env.getStateChangedAt());
                continue;
            }
            logger.debug(
                    "Idle timeout reached for env {} (quiet since {}); suspending",
                    env.getId(),
                    env.getStateChangedAt());
            failRow(
                    "suspending idle sandbox",
                    () -> executionEnvironmentService.sleepEnvironmentInternal(env),
                    env.getId());
        }
    }

    private void expireStaleSnapshots(Instant now) {
        for (ExecutionEnvironment env : environmentRepository.findExpiredSleeping(now.minus(retentionPeriod))) {
            if (env.getType() != ExecutionEnvironmentType.SANDBOX || env.getProvider() == null) {
                continue;
            }
            logger.debug("Expiring env {} (state_changed_at={})", env.getId(), env.getStateChangedAt());
            failRow("expiring sandbox", () -> executionEnvironmentService.expireEnvironment(env), env.getId());
        }
    }

    /**
     * Lost runtime rule. A CONNECTED row whose runner is absent past startup-grace has lost its
     * runtime: remove owned compute without committing a snapshot, then SLEEPING when a snapshot
     * exists, else TERMINATED. RUNNING executions move to UNKNOWN; IDLE executions are unchanged
     * for SLEEPING and move to UNKNOWN for TERMINATED.
     */
    private void applyLostRuntimeRule(Instant now) {
        List<ExecutionEnvironment> rows = environmentRepository.findByTypeAndStatusIn(
                ExecutionEnvironmentType.SANDBOX,
                List.of(EnvironmentStatus.CONNECTED, EnvironmentStatus.PENDING_CONNECT, EnvironmentStatus.SLEEPING));
        Map<SandboxProvider, Set<UUID>> active = new HashMap<>();
        Map<SandboxProvider, Set<UUID>> retained = new HashMap<>();
        for (ExecutionEnvironment env : rows) {
            SandboxProvider provider = providerFor(env);
            if (provider == null || !elapsed(env.getStateChangedAt(), now, startupGrace)) {
                continue;
            }
            failRow("checking row resources", () -> checkRowResources(env, provider, active, retained), env.getId());
        }
    }

    private void checkRowResources(
            ExecutionEnvironment env,
            SandboxProvider provider,
            Map<SandboxProvider, Set<UUID>> active,
            Map<SandboxProvider, Set<UUID>> retained) {
        boolean hasActive = active.computeIfAbsent(provider, SandboxProvider::listActiveEnvIds)
                .contains(env.getId());
        boolean hasRetained = retained.computeIfAbsent(provider, SandboxProvider::listRetainedEnvIds)
                .contains(env.getId());
        switch (env.getStatus()) {
            case SLEEPING -> {
                if (hasActive) {
                    logger.warn("SLEEPING env {} has leftover runtime elements; terminating", env.getId());
                    provider.terminateSandbox(env.getId());
                } else if (!hasRetained) {
                    logger.warn(
                            "SLEEPING env {} has no runtime and no suspended state; marking TERMINATED", env.getId());
                    executionEnvironmentService.reconcilePurgedEnvironment(env);
                }
            }
            case CONNECTED -> {
                if (provider.isSandboxRunning(env.getId())) {
                    return;
                }
                logger.warn("CONNECTED env {} lost its runtime; removing compute", env.getId());
                provider.terminateSandbox(env.getId());
                if (provider.isRetained(env.getId())) {
                    executionEnvironmentService.suspendAfterLostRuntime(env);
                } else {
                    executionEnvironmentService.reconcilePurgedEnvironment(env);
                }
            }
            case PENDING_CONNECT -> {
                if (!hasActive && !hasRetained) {
                    logger.warn(
                            "Sandbox {} recorded as {} with no runtime and no suspended state; marking TERMINATED",
                            env.getId(),
                            env.getStatus());
                    executionEnvironmentService.reconcilePurgedEnvironment(env);
                }
            }
            default -> {
                // No other status carries resources through this rule.
            }
        }
    }

    private static void failRow(String action, Runnable row, UUID envId) {
        try {
            row.run();
        } catch (RuntimeException e) {
            logger.warn("Skipping resource check for env {} ({}): {}", envId, action, e.getMessage());
        }
    }

    /** Purges owned compute and snapshots with no row, and of terminated environments. */
    private void reapOrphans(Instant now) {
        Map<SandboxProvider, Set<UUID>> activeByProvider = new LinkedHashMap<>();
        Map<SandboxProvider, Set<UUID>> retainedByProvider = new LinkedHashMap<>();
        for (SandboxProvider provider : sandboxProviders) {
            try {
                activeByProvider.put(provider, provider.listActiveEnvIds());
                retainedByProvider.put(provider, provider.listRetainedEnvIds());
            } catch (RuntimeException e) {
                logger.warn("Skipping orphan reap: {} listing failed: {}", provider.getProviderType(), e.getMessage());
                return;
            }
        }
        Set<UUID> allActive = new HashSet<>();
        Set<UUID> allRetained = new HashSet<>();
        for (SandboxProvider provider : sandboxProviders) {
            Set<UUID> activeIds = activeByProvider.getOrDefault(provider, Set.of());
            allActive.addAll(activeIds);
            activeIds.forEach(
                    id -> failRow("reaping orphan compute", () -> reapComputeIfOrphaned(provider, id, now), id));
            Set<UUID> retainedIds = retainedByProvider.getOrDefault(provider, Set.of());
            allRetained.addAll(retainedIds);
            retainedIds.forEach(
                    id -> failRow("reaping orphan snapshot", () -> reapSnapshotIfOrphaned(provider, id, now), id));
        }
        orphanFirstSeen.keySet().retainAll(allActive);
        orphanSnapshotFirstSeen.keySet().retainAll(allRetained);
    }

    private void reapComputeIfOrphaned(SandboxProvider provider, UUID envId, Instant now) {
        ExecutionEnvironment env = environmentRepository.findById(envId).orElse(null);
        if (env == null) {
            Instant firstSeen = orphanFirstSeen.putIfAbsent(envId, now);
            if (elapsed(firstSeen, now, startupGrace)) {
                logger.info("Purging orphaned sandbox compute {}", envId);
                provider.captureSidecarLogs(envId);
                provider.terminateSandbox(envId);
                orphanFirstSeen.remove(envId);
            }
            return;
        }
        if (env.getStatus() == EnvironmentStatus.TERMINATED
                && env.getProvider() != null
                && elapsed(env.getStateChangedAt(), now, startupGrace)) {
            logger.info("Purging compute of terminated sandbox {}", envId);
            provider.terminateSandbox(envId);
        }
    }

    private void reapSnapshotIfOrphaned(SandboxProvider provider, UUID envId, Instant now) {
        ExecutionEnvironment env = environmentRepository.findById(envId).orElse(null);
        if (env != null && env.getStatus() != EnvironmentStatus.TERMINATED) {
            return;
        }
        if (env != null && (env.getProvider() == null || !elapsed(env.getStateChangedAt(), now, startupGrace))) {
            return;
        }
        Instant firstSeen = orphanSnapshotFirstSeen.putIfAbsent(envId, now);
        if (elapsed(firstSeen, now, startupGrace)) {
            logger.info("Purging orphaned sandbox snapshot {}", envId);
            provider.removeSnapshot(envId);
            orphanFirstSeen.remove(envId);
            orphanSnapshotFirstSeen.remove(envId);
        }
    }

    /** A TERMINATED environment can never run its executions; nothing else strands them. */
    private void failExecutionsOfTerminatedEnvironments() {
        for (SandboxExecution execution : sandboxExecutionRepository.findByStatusInAndEnvironment_StatusIn(
                List.of(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE),
                List.of(EnvironmentStatus.TERMINATED))) {
            logger.info(
                    "Failing execution {} of terminated environment {}",
                    execution.getId(),
                    execution.getEnvironment().getId());
            executionEnvironmentService.failStrandedExecution(execution.getId());
        }
    }

    private SandboxProvider providerFor(ExecutionEnvironment env) {
        if (env.getProvider() == null) {
            return null;
        }
        ExecutionProviderType type = env.getProvider().getType();
        for (SandboxProvider provider : sandboxProviders) {
            if (provider.getProviderType() == type) {
                return provider;
            }
        }
        return null;
    }

    private static boolean elapsed(Instant since, Instant now, Duration limit) {
        return since != null && Duration.between(since, now).compareTo(limit) >= 0;
    }
}
