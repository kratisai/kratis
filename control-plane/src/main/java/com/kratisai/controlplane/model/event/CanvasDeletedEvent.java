package com.kratisai.controlplane.model.event;

import java.util.Objects;
import java.util.UUID;

public record CanvasDeletedEvent(UUID teamId, UUID chatId, String documentId) {
    public CanvasDeletedEvent {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(chatId, "chatId is required");
        Objects.requireNonNull(documentId, "documentId is required");
    }
}
