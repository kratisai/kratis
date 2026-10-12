package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.time.Duration;
import java.time.Instant;
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
 * Sandbox lifecycle sweeps. Each tick runs three phases over one observation
 * of the world: reap runtimes that no longer match their recorded state, suspend idle sandboxes,
 * and expire stale suspend snapshots. All state transitions go through {@link
 * ExecutionEnvironmentService}; the providers only ever see environment ids.
 */
@Service
@ConditionalOnProperty(value = "kratis.sandbox.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class SandboxLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxLifecycleService.class);

    private final ExecutionEnvironmentRepository environmentRepository;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final List<SandboxProvider> sandboxProviders;
    private final Duration idleTimeout;
    private final Duration startupGrace;
    private final Duration retentionPeriod;

    /** When a runtime/state mismatch was first observed, keyed by environment id. */
    private final Map<UUID, Instant> mismatchFirstSeen = new ConcurrentHashMap<>();

    public SandboxLifecycleService(
            ExecutionEnvironmentRepository environmentRepository,
            ExecutionEnvironmentService executionEnvironmentService,
            List<SandboxProvider> sandboxProviders,
            SandboxProperties sandboxProperties) {
        this.environmentRepository = environmentRepository;
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
            if (graceExpired(envId, now)) {
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
        if (!graceExpired(envId, now)) {
            return;
        }
        logger.info("Reaping sandbox {} whose runtime no longer matches status {}", envId, env.getStatus());
        executionEnvironmentService.reapEnvironment(env);
        mismatchFirstSeen.remove(envId);
    }

    private boolean graceExpired(UUID envId, Instant now) {
        Instant firstSeen = mismatchFirstSeen.putIfAbsent(envId, now);
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
}
