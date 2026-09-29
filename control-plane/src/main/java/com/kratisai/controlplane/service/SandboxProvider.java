package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionProviderType;
import java.util.List;

public interface SandboxProvider {
    ExecutionProviderType getProviderType(); // e.g., "local-docker"

    // Spawns a sandbox container/workspace, returning its unique identifier (e.g.
    // Container ID)
    String spawnSandbox(ExecutionEnvironment environment, String token);

    // Initialises the workspace for a sandbox container
    void initializeWorkspace(String containerId);

    // Terminates a sandbox container/workspace
    void terminateSandbox(String containerId);

    // Returns a list of active sandbox/container IDs managed by this provider
    List<String> getActiveSandboxIds();
}
