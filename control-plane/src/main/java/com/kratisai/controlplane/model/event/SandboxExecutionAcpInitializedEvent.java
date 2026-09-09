package com.kratisai.controlplane.model.event;

import java.util.UUID;

/** ACP handshake completed for an execution; UI fan-out listens AFTER_COMMIT. */
public record SandboxExecutionAcpInitializedEvent(
        UUID teamId, UUID executionId, String sessionId, String agentName, String agentVersion) {}
