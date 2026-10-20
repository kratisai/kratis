package com.kratisai.controlplane.model.event;

import java.util.Objects;
import java.util.UUID;

/** Environment slept with the execution resumable; usage finalization listens AFTER_COMMIT. */
public record SandboxExecutionSuspendedEvent(UUID teamId, UUID executionId, UUID environmentId) {
    public SandboxExecutionSuspendedEvent {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(environmentId, "environmentId is required");
    }
}
