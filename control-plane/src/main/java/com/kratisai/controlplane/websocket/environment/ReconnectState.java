package com.kratisai.controlplane.websocket.environment;

import java.util.List;

public record ReconnectState(
        boolean isReconnect,
        String activeAcpSessionId,
        boolean hasActiveAgent,
        long lastEventSequence,
        List<String> pendingHitlIds) {}
