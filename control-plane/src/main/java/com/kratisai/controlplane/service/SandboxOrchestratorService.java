package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ExecutionProviderType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class SandboxOrchestratorService {
    private final Map<ExecutionProviderType, SandboxProvider> providers = new ConcurrentHashMap<>();

    public SandboxOrchestratorService(List<SandboxProvider> providerList) {
        for (SandboxProvider provider : providerList) {
            providers.put(provider.getProviderType(), provider);
        }
    }

    public SandboxProvider getProvider(ExecutionProviderType type) {
        SandboxProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported sandbox provider type: " + type);
        }
        return provider;
    }
}
