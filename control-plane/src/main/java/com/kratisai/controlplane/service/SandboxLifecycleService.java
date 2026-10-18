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
 * Sandbox lifecycle sweeps. Each tick runs over one observation of the world: reap runtimes that no
 * longer match their recorded state, suspend idle sandboxes, expire stale suspend snapshots, and
 * reconcile environments whose provider resources were purged out of band. All state transitions go
 * through {@link ExecutionEnvironmentService}; the providers only ever see environment ids.
 */
@Service
@ConditionalOnProperty(value = "kratis.sandbox.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class SandboxLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxLifecycleService.class);

    private final ExecutionEnvironmentRepository environmentRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final List<SandboxProvider> sandboxProviders;
    private final Duration idleTimeout;
    private final Duration startupGrace;
    private final Duration retentionPeriod;

    /** When a runtime/state mismatch was first observed, keyed by environment id. */
    private final Map<UUID, Instant> mismatchFirstSeen = new ConcurrentHashMap<>();

    /** When a live/sleeping environment was first seen without recoverable provider resources. */
    private final Map<UUID, Instant> purgedFirstSeen = new ConcurrentHashMap<>();

    /** When an execution was first seen running for an environment that can no longer run it. */
    private final Map<UUID, Instant> strandedExecutionFirstSeen = new ConcurrentHashMap<>();

    public SandboxLifecycleService(
            ExecutionEnvironmentRepository environmentRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            ExecutionEnvironmentService executionEnvironmentService,
            List<SandboxProvider> sandboxProviders,
            SandboxProperties sandboxProperties) {
        this.environmentRepository = environmentRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.executionEnvironmentService = executionEnvironmentService;
        this.sandboxProviders = sandboxProviders;
        this.idleTimeout = sandboxProperties.getLifecycle().getIdleTimeout();
        this.startupGrace = sandboxProperties.getLifecycle().getStartupGrace();
        this.retentionPeriod = sandboxProperties.getLifecycle().getRetentionPeriod();
    }

    @Scheduled(fixedRateString = "${kratis.sandbox.lifecycle.poll-interval:30s}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        reapMismatchedSandboxes(now);
        suspendIdleSandboxes(now);
        expireStaleSnapshots(now);
        reconcilePurgedSandboxes(now);
        reconcileStrandedExecutions(now);
    }

    /**
     * Settles executions left RUNNING or IDLE by an environment that is DISCONNECTED or TERMINATED
     * as {@link SandboxExecutionStatus#UNKNOWN}: the sandbox is gone, so their outcome was never
     * observed, and the UI must stop offering terminal, steer and halt controls. SLEEPING is
     * excluded (a sleeping sandbox keeps its execution IDLE for resume), and the startup grace
     * applies so the brief DISCONNECTED a sleep or resume teardown can publish is never mistaken
     * for a dead environment.
     */
    private void reconcileStrandedExecutions(Instant now) {
        List<SandboxExecution> stranded = sandboxExecutionRepository.findByStatusInAndEnvironment_StatusIn(
                List.of(SandboxExecutionStatus.RUNNING, SandboxExecutionStatus.IDLE),
                List.of(EnvironmentStatus.DISCONNECTED, EnvironmentStatus.TERMINATED));
        Set<UUID> strandedIds = new HashSet<>();
        for (SandboxExecution execution : stranded) {
            strandedIds.add(execution.getId());
            if (!graceExpired(strandedExecutionFirstSeen, execution.getId(), now)) {
                continue;
            }
            logger.info(
                    "Failing execution {} stranded by environment {}",
                    execution.getId(),
                    execution.getEnvironment().getId());
            executionEnvironmentService.failStrandedExecution(execution.getId());
            strandedExecutionFirstSeen.remove(execution.getId());
        }
        strandedExecutionFirstSeen.keySet().retainAll(strandedIds);
    }

    /**
     * Reaps sandboxes whose runtime resources no longer match their recorded state: runtimes with
     * no environment row (orphans), rows whose runtime is absent or stopped while not connected
     * (delinquents). Each mismatch must persist across the startup grace before it is acted on, so
     * in-flight spawns and resumes are never reaped mid-provision.
     */
    private void reapMismatchedSandboxes(Instant now) {
        Map<SandboxProvider, Set<UUID>> activeByProvider = new LinkedHashMap<>();
        Set<UUID> allActive = new HashSet<>();
        for (SandboxProvider provider : sandboxProviders) {
            Set<UUID> active = provider.listActiveEnvIds();
            activeByProvider.put(provider, active);
            allActive.addAll(active);
        }

        activeByProvider.forEach((provider, activeEnvIds) -> {
            for (UUID envId : activeEnvIds) {
                reapMismatch(provider, envId, now);
            }
            provider.pruneOrphanNetworks(startupGrace);
        });
        mismatchFirstSeen.keySet().retainAll(allActive);
    }

    private void reapMismatch(SandboxProvider provider, UUID envId, Instant now) {
        ExecutionEnvironment env = environmentRepository.findById(envId).orElse(null);
        if (env == null) {
            if (graceExpired(mismatchFirstSeen, envId, now)) {
                logger.info("Destroying orphaned sandbox runtime {}", envId);
                provider.captureSidecarLogs(envId);
                provider.destroy(envId);
                mismatchFirstSeen.remove(envId);
            }
            return;
        }
        if (env.getStatus() == EnvironmentStatus.CONNECTED && provider.isSandboxRunning(envId)) {
            mismatchFirstSeen.remove(envId);
            return;
        }
        if (env.getType() != ExecutionEnvironmentType.SANDBOX || env.getProvider() == null) {
            return;
        }
        if (!graceExpired(mismatchFirstSeen, envId, now)) {
            return;
        }
        logger.info("Reaping sandbox {} whose runtime no longer matches status {}", envId, env.getStatus());
        executionEnvironmentService.reapEnvironment(env);
        mismatchFirstSeen.remove(envId);
    }

    private boolean graceExpired(Map<UUID, Instant> firstSeenByEnv, UUID envId, Instant now) {
        Instant firstSeen = firstSeenByEnv.putIfAbsent(envId, now);
        return firstSeen != null && Duration.between(firstSeen, now).compareTo(startupGrace) >= 0;
    }

    /** Suspends connected sandboxes that have seen no activity for the idle timeout. */
    private void suspendIdleSandboxes(Instant now) {
        for (Object[] row : environmentRepository.findConnectedEnvironmentsWithLastActivity()) {
            ExecutionEnvironment env = (ExecutionEnvironment) row[0];
            Instant lastActivity = (Instant) row[1];
            long runningExecutions = ((Number) row[2]).longValue();
            if (env.getType() != ExecutionEnvironmentType.SANDBOX) {
                continue;
            }
            if (runningExecutions > 0) {
                continue;
            }
            if (lastActivity == null || Duration.between(lastActivity, now).compareTo(idleTimeout) < 0) {
                continue;
            }
            try {
                if (!executionEnvironmentService.isSandboxRunning(env)) {
                    executionEnvironmentService.reconcileStoppedContainer(env);
                } else {
                    executionEnvironmentService.sleepEnvironmentInternal(env);
                }
            } catch (Exception e) {
                logger.error("Failed to suspend idle sandbox {}: {}", env.getId(), e.getMessage());
            }
        }
    }

    /** Destroys suspend snapshots that outlived their retention window. */
    private void expireStaleSnapshots(Instant now) {
        Instant cutoff = now.minus(retentionPeriod);
        for (ExecutionEnvironment env : environmentRepository.findExpiredSleeping(cutoff)) {
            if (env.getType() != ExecutionEnvironmentType.SANDBOX || env.getProvider() == null) {
                continue;
            }
            try {
                executionEnvironmentService.expireEnvironment(env);
            } catch (Exception e) {
                logger.error("Failed to expire sandbox {}: {}", env.getId(), e.getMessage());
            }
        }
    }

    private void reconcilePurgedSandboxes(Instant now) {
        List<ExecutionEnvironment> candidates = environmentRepository.findByTypeAndStatusIn(
                ExecutionEnvironmentType.SANDBOX,
                List.of(EnvironmentStatus.CONNECTED, EnvironmentStatus.PENDING_CONNECT, EnvironmentStatus.SLEEPING));
        if (candidates.isEmpty()) {
            purgedFirstSeen.clear();
            return;
        }

        Map<ExecutionProviderType, SandboxProvider> providersByType = new HashMap<>();
        for (SandboxProvider provider : sandboxProviders) {
            providersByType.put(provider.getProviderType(), provider);
        }
        Map<SandboxProvider, Set<UUID>> snapshotsByProvider = new HashMap<>();
        Map<SandboxProvider, Set<UUID>> runtimesByProvider = new HashMap<>();
        Set<UUID> candidateIds = new HashSet<>();
        for (ExecutionEnvironment env : candidates) {
            SandboxProvider provider = env.getProvider() == null
                    ? null
                    : providersByType.get(env.getProvider().getType());
            if (provider == null) {
                continue;
            }
            candidateIds.add(env.getId());
            Set<UUID> snapshottedEnvIds =
                    snapshotsByProvider.computeIfAbsent(provider, SandboxProvider::listSnapshotEnvIds);
            Set<UUID> runnableEnvIds = runtimesByProvider.computeIfAbsent(provider, SandboxProvider::listActiveEnvIds);
            if (snapshottedEnvIds.contains(env.getId()) || runnableEnvIds.contains(env.getId())) {
                purgedFirstSeen.remove(env.getId());
                continue;
            }
            if (!graceExpired(purgedFirstSeen, env.getId(), now)) {
                continue;
            }
            logger.info(
                    "Sandbox {} was recorded as {} but has neither a runtime nor a snapshot; reconciling as purged",
                    env.getId(),
                    env.getStatus());
            executionEnvironmentService.reconcilePurgedEnvironment(env);
            purgedFirstSeen.remove(env.getId());
        }
        purgedFirstSeen.keySet().retainAll(candidateIds);
    }
}
