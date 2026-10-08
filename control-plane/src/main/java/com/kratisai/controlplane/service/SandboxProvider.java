package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import java.util.List;

public interface SandboxProvider {
    ExecutionProviderType getProviderType();

    String spawnSandbox(ExecutionEnvironment environment, String token);

    void initializeWorkspace(String containerId);

    boolean isContainerRunning(String envId);

    void suspend(String envId);

    String resume(String envId, ExecutionEnvironment environment, String token);

    void destroy(String envId);

    void terminateSandbox(String containerId);

    List<String> getActiveSandboxIds();
}
