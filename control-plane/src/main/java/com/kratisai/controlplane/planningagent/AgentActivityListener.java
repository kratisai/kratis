package com.kratisai.controlplane.agentloop;

import java.util.UUID;

@FunctionalInterface
public interface AgentActivityListener {
    void onAgentActivity(UUID chatId);
}
