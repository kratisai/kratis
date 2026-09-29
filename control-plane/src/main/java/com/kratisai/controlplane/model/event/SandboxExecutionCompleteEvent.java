package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
import java.util.UUID;

/** Completes execution; UI fan-out and usage finalization both listen AFTER_COMMIT. */
public record SandboxExecutionCompleteEvent(
        UUID teamId, UUID executionId, int exitCode, SandboxExecutionStatus status, String reason) {

    public SandboxExecutionCompleteEvent(UUID teamId, UUID executionId, int exitCode, SandboxExecutionStatus status) {
        this(teamId, executionId, exitCode, status, null);
    }
}
