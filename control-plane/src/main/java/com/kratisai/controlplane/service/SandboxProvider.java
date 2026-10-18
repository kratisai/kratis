package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public interface SandboxProvider {

    ExecutionProviderType getProviderType();

    void spawnSandbox(ExecutionEnvironment environment);

    void initializeWorkspace(UUID envId, String token);

    boolean isSandboxRunning(UUID envId);

    void suspend(UUID envId);

    void resume(ExecutionEnvironment environment);

    void terminateSandbox(UUID envId);

    void destroy(UUID envId);

    void captureSidecarLogs(UUID envId);

    Set<UUID> listActiveEnvIds();

    Set<UUID> listSnapshotEnvIds();

    void pruneOrphanNetworks(Duration gracePeriod);
}
