package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
import java.util.UUID;

/** Internal lifecycle event for execution activity settlement and usage finalization. */
public record SandboxExecutionCompleteEvent(
        UUID teamId,
        UUID executionId,
        int exitCode,
        SandboxExecutionStatus status,
        String reason,
        boolean unexpectedProcessExit) {}
