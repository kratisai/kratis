package com.kratisai.controlplane.planningagent;

import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;

public record PlanningContext(UUID teamId, UUID chatId, String messageId) {

    public PlanningContext(UUID teamId, UUID chatId) {
        this(teamId, chatId, UUID.randomUUID().toString());
    }

    public ToolContext toToolContext() {
        return new ToolContext(Map.of("teamId", teamId, "chatId", chatId));
    }
}
