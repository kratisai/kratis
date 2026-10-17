package com.kratisai.controlplane.planningagent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Per-turn planning state. A null {@code continuationDirective} means a normal user turn; otherwise
 * the turn continues a paused run and the directive is injected into history without being persisted.
 */
public record PlanningContext(
        UUID teamId,
        UUID chatId,
        String messageId,
        @Nullable String continuationDirective) {

    public PlanningContext(UUID teamId, UUID chatId) {
        this(teamId, chatId, UUID.randomUUID().toString(), null);
    }

    public static PlanningContext continuation(UUID teamId, UUID chatId, String continuationDirective) {
        return new PlanningContext(
                teamId,
                chatId,
                UUID.randomUUID().toString(),
                Objects.requireNonNull(continuationDirective, "continuationDirective is required"));
    }

    public ToolContext toToolContext() {
        return new ToolContext(Map.of("teamId", teamId, "chatId", chatId));
    }
}
