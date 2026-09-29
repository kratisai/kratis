package com.kratisai.controlplane.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages WebSocket session subscriptions to broadcast event channels.
 *
 * <p>Only broadcast events (e.g., ingestion status updates) use this registry. Direct
 * request-response events (chat, canvas, telemetry) are correlated by JSON-RPC message ID and do
 * not require subscription tracking.
 *
 * <p>Clients must explicitly subscribe to the team they are currently viewing. This prevents
 * unnecessary event delivery to sessions that don't need them (e.g., events for Team B to a user
 * currently viewing Team A).
 *
 * <p>Thread-safe for concurrent access from multiple WebSocket handler threads.
 */
@Component
public class SubscriptionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionRegistry.class);

    /** Map of sessionId -> Subscribed Team ID. */
    private final Map<String, UUID> subscriptions = new ConcurrentHashMap<>();

    public boolean subscribe(String sessionId, UUID teamId) {
        UUID old = subscriptions.put(sessionId, teamId);
        if (!teamId.equals(old)) {
            logger.debug("Session {} subscribed to team {}", sessionId, teamId);
            return true;
        }
        return false;
    }

    public boolean unsubscribe(String sessionId, UUID teamId) {
        boolean removed = subscriptions.remove(sessionId, teamId);
        if (removed) {
            logger.debug("Session {} unsubscribed from team {}", sessionId, teamId);
        }
        return removed;
    }

    public boolean isSubscribed(String sessionId, UUID teamId) {
        UUID current = subscriptions.get(sessionId);
        return teamId.equals(current);
    }

    public Set<String> getSubscribers(UUID teamId) {
        return subscriptions.entrySet().stream()
                .filter(entry -> teamId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public UUID getSubscription(String sessionId) {
        return subscriptions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        UUID removed = subscriptions.remove(sessionId);
        if (removed != null) {
            logger.debug("Removed subscription for session {}: {}", sessionId, removed);
        }
    }
}
