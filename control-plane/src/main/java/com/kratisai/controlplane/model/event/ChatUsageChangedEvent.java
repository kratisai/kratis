package com.kratisai.controlplane.model.event;

import java.util.Objects;
import java.util.UUID;

public record ChatUsageChangedEvent(UUID teamId, UUID chatId) {
    public ChatUsageChangedEvent {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(chatId, "chatId is required");
    }
}
