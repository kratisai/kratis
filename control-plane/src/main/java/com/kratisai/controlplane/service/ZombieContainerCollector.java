package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(value = "kratis.zombie-collector.enabled", havingValue = "true", matchIfMissing = true)
public class ZombieContainerCollector {

    private static final Logger logger = LoggerFactory.getLogger(ZombieContainerCollector.class);

    private final ExecutionEnvironmentRepository environmentRepository;
    private final List<SandboxProvider> sandboxProviders;
    private final Optional<ProcessExecutor> processExecutor;
    private final String instanceId;
    private final long gracePeriodSeconds;

    // Track when resources were first seen to respect the grace period
    private final Map<String, Instant> orphanFirstSeen = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> delinquentFirstSeen = new ConcurrentHashMap<>();
    private final Map<String, Instant> danglingNetworkFirstSeen = new ConcurrentHashMap<>();

    public ZombieContainerCollector(
            ExecutionEnvironmentRepository environmentRepository,
            List<SandboxProvider> sandboxProviders,
            Optional<ProcessExecutor> processExecutor,
            @Value("${kratis.instance.id:default}") String instanceId,
            @Value("${kratis.zombie-collector.grace-period-seconds:30}") long gracePeriodSeconds) {
        this.environmentRepository = environmentRepository;
        this.sandboxProviders = sandboxProviders;
        this.processExecutor = processExecutor;
        this.instanceId = instanceId;
        this.gracePeriodSeconds = gracePeriodSeconds;
    }

    @Scheduled(fixedRateString = "${kratis.zombie-collector.fixed-rate-ms:10000}")
    @Transactional
    public void collectZombies() {
        logger.debug("Starting zombie container collection cycle...");
        Instant now = Instant.now();
        Set<String> allRunningSandboxIds = new HashSet<>();

        for (SandboxProvider provider : sandboxProviders) {
            List<String> runningSandboxIds = provider.getActiveSandboxIds();
            if (runningSandboxIds == null || runningSandboxIds.isEmpty()) {
                continue;
            }
            allRunningSandboxIds.addAll(runningSandboxIds);

            for (String sandboxId : runningSandboxIds) {
                Optional<ExecutionEnvironment> envOpt = environmentRepository.findByContainerId(sandboxId);
                if (envOpt.isEmpty()) {
                    // Scenario 1: Orphaned sandbox (no DB record matches)
                    Instant firstSeen = orphanFirstSeen.computeIfAbsent(sandboxId, k -> now);
                    long elapsed = Duration.between(firstSeen, now).toSeconds();
                    if (elapsed >= gracePeriodSeconds) {
                        logger.warn(
                                "Orphaned sandbox {} of provider {} exceeded grace period ({}s). Terminating.",
                                sandboxId,
                                provider.getProviderType(),
                                elapsed);
                        provider.terminateSandbox(sandboxId);
                        orphanFirstSeen.remove(sandboxId);
                    } else {
                        logger.debug(
                                "Orphaned sandbox {} of provider {} tracked ({}s elapsed / {}s grace)",
                                sandboxId,
                                provider.getProviderType(),
                                elapsed,
                                gracePeriodSeconds);
                    }
                } else {
                    ExecutionEnvironment env = envOpt.get();
                    if (env.getStatus() == EnvironmentStatus.CONNECTED) {
                        // Reset delinquency tracking since it is healthy and connected
                        delinquentFirstSeen.remove(env.getId());
                    } else {
                        // Scenario 2: Delinquent sandbox (DB record exists but environment is not connected)
                        if (env.getLastHeartbeat() != null) {
                            long elapsed = Duration.between(env.getLastHeartbeat(), now)
                                    .toSeconds();
                            if (elapsed >= gracePeriodSeconds) {
                                logger.warn(
                                        "Delinquent environment {} (sandbox {}) missed heartbeat for {}s (grace {}s). Terminating.",
                                        env.getId(),
                                        sandboxId,
                                        elapsed,
                                        gracePeriodSeconds);
                                terminateDelinquent(env, provider);
                            }
                        } else {
                            // Never connected: use first seen delinquent tracking
                            Instant firstSeen = delinquentFirstSeen.computeIfAbsent(env.getId(), k -> now);
                            long elapsed = Duration.between(firstSeen, now).toSeconds();
                            if (elapsed >= gracePeriodSeconds) {
                                logger.warn(
                                        "Environment {} (sandbox {}) failed to establish initial connection within {}s. Terminating.",
                                        env.getId(),
                                        sandboxId,
                                        elapsed);
                                terminateDelinquent(env, provider);
                            } else {
                                logger.debug(
                                        "Environment {} (sandbox {}) awaiting initial connection ({}s elapsed)",
                                        env.getId(),
                                        sandboxId,
                                        elapsed);
                            }
                        }
                    }
                }
            }
        }

        // Clean up tracking maps of sandboxes no longer running
        orphanFirstSeen.keySet().removeIf(id -> !allRunningSandboxIds.contains(id));
        delinquentFirstSeen.keySet().removeIf(uuid -> {
            Optional<ExecutionEnvironment> opt = environmentRepository.findById(uuid);
            return opt.isEmpty()
                    || opt.get().getContainerId() == null
                    || !allRunningSandboxIds.contains(opt.get().getContainerId());
        });

        // Prune dangling orphan networks created by this Kratis instance
        pruneOrphanNetworks();
    }

    private void pruneOrphanNetworks() {
        if (processExecutor.isEmpty()) {
            return;
        }
        try {
            ProcessExecutor executor = processExecutor.get();
            Instant now = Instant.now();
            // List networks created by this instance with role=network
            List<String> listCmd = List.of(
                    "docker",
                    "network",
                    "ls",
                    "--filter",
                    "label=kratis.instance.id=" + instanceId,
                    "--filter",
                    "label=kratis.role=network",
                    "--format",
                    "{{.Name}}");
            ProcessExecutor.ProcessResult listResult = executor.execute(listCmd, null, null);
            if (listResult.exitCode() != 0) {
                return;
            }
            String output = new String(listResult.output()).trim();
            Set<String> listedNetworks = new HashSet<>();
            if (!output.isEmpty()) {
                String[] networkNames = output.split("\\s+");
                for (String netName : networkNames) {
                    if (netName.isBlank()) {
                        continue;
                    }
                    listedNetworks.add(netName);
                    // Check if any containers are attached to this network
                    List<String> inspectCmd =
                            List.of("docker", "network", "inspect", "--format", "{{len .Containers}}", netName);
                    ProcessExecutor.ProcessResult inspectResult = executor.execute(inspectCmd, null, null);
                    if (inspectResult.exitCode() != 0) {
                        continue;
                    }
                    String countStr = new String(inspectResult.output()).trim();
                    if ("0".equals(countStr)) {
                        Instant firstSeen = danglingNetworkFirstSeen.computeIfAbsent(netName, k -> now);
                        long elapsed = Duration.between(firstSeen, now).toSeconds();
                        if (elapsed >= gracePeriodSeconds) {
                            logger.info("Pruning dangling kratis network with 0 attached containers: {}", netName);
                            executor.execute(List.of("docker", "network", "rm", netName), null, null);
                            danglingNetworkFirstSeen.remove(netName);
                        } else {
                            logger.debug(
                                    "Dangling kratis network {} tracked ({}s elapsed / {}s grace)",
                                    netName,
                                    elapsed,
                                    gracePeriodSeconds);
                        }
                    } else {
                        // Network has attached containers; clear any dangling tracking
                        danglingNetworkFirstSeen.remove(netName);
                    }
                }
            }
            // Drop tracking for networks that no longer exist
            danglingNetworkFirstSeen.keySet().removeIf(name -> !listedNetworks.contains(name));
        } catch (Exception e) {
            logger.debug("Failed during orphan network pruning: {}", e.getMessage());
        }
    }

    private void terminateDelinquent(ExecutionEnvironment env, SandboxProvider provider) {
        String sandboxId = env.getContainerId();
        if (sandboxId != null) {
            provider.terminateSandbox(sandboxId);
        }
        env.setContainerId(null);
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        environmentRepository.save(env);
        delinquentFirstSeen.remove(env.getId());
        logger.info("Successfully cleaned up delinquent environment {}", env.getId());
    }
}
