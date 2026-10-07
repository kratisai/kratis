package com.kratisai.controlplane.model.event;

import java.util.Objects;
import java.util.UUID;

public record SandboxExecutionDiffChangedEvent(UUID teamId, UUID chatId, UUID executionId) {
    public SandboxExecutionDiffChangedEvent {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(chatId, "chatId is required");
        Objects.requireNonNull(executionId, "executionId is required");
    }
}
