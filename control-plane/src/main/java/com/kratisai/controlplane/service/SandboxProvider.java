package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
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

    /** Every environment with any live runtime element left, full or partial. */
    Set<UUID> listActiveEnvIds();

    /** Every environment with a retained snapshot on this host. */
    Set<UUID> listRetainedEnvIds();

    default boolean isRetained(UUID envId) {
        return envId != null && listRetainedEnvIds().contains(envId);
    }

    void removeSnapshot(UUID envId);
}
