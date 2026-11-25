package com.kratisai.controlplane.model.event;

import java.util.Objects;
import java.util.UUID;

public record ChatTurnCompletedEvent(UUID teamId, UUID chatId) {
    public ChatTurnCompletedEvent {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(chatId, "chatId is required");
    }
}
