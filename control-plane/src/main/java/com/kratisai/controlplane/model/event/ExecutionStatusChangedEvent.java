package com.kratisai.controlplane.model.event;

import java.util.Objects;
import java.util.UUID;

public record ExecutionStatusChangedEvent(UUID teamId, UUID chatId, UUID executionId) {
    public ExecutionStatusChangedEvent {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(chatId, "chatId is required");
        Objects.requireNonNull(executionId, "executionId is required");
    }
}
