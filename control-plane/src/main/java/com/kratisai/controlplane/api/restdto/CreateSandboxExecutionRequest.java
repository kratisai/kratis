package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.AgentHarness;
import java.util.UUID;

public record CreateSandboxExecutionRequest(
        UUID providerId,
        UUID environmentId,
        UUID credentialId,
        AgentHarness harness,
        String canvasId,
        UUID modelProviderId,
        String modelName) {}
