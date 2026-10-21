package com.kratisai.controlplane.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks the chat and execution subscriptions held by each web-UI WebSocket session, so a repeated
 * subscribe from the same session is acknowledged without replaying persisted state again.
 *
 * <p>Thread-safe for concurrent access from multiple WebSocket handler threads.
 */
@Component
public class ChatSubscriptionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatSubscriptionRegistry.class);

    private final Map<String, Set<UUID>> chatSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> executionSubscriptions = new ConcurrentHashMap<>();

    /** Returns {@code true} when the session was not already subscribed to the chat. */
    public boolean subscribeChat(String sessionId, UUID chatId) {
        boolean added = add(chatSubscriptions, sessionId, chatId);
        if (added) {
            logger.debug("Client session {} subscribed to chat {}", sessionId, chatId);
        }
        return added;
    }

    /** Returns {@code true} when a chat subscription was removed. */
    public boolean unsubscribeChat(String sessionId, UUID chatId) {
        boolean removed = remove(chatSubscriptions, sessionId, chatId);
        if (removed) {
            logger.debug("Client session {} unsubscribed from chat {}", sessionId, chatId);
        }
        return removed;
    }

    public boolean isChatSubscribed(String sessionId, UUID chatId) {
        return contains(chatSubscriptions, sessionId, chatId);
    }

    /** Returns {@code true} when the session was not already subscribed to the execution. */
    public boolean subscribeExecution(String sessionId, UUID executionId) {
        boolean added = add(executionSubscriptions, sessionId, executionId);
        if (added) {
            logger.debug("Client session {} subscribed to execution {}", sessionId, executionId);
        }
        return added;
    }

    /** Returns {@code true} when an execution subscription was removed. */
    public boolean unsubscribeExecution(String sessionId, UUID executionId) {
        boolean removed = remove(executionSubscriptions, sessionId, executionId);
        if (removed) {
            logger.debug("Client session {} unsubscribed from execution {}", sessionId, executionId);
        }
        return removed;
    }

    public boolean isExecutionSubscribed(String sessionId, UUID executionId) {
        return contains(executionSubscriptions, sessionId, executionId);
    }

    /** Drops every subscription held by a closed session. */
    public void removeSession(String sessionId) {
        chatSubscriptions.remove(sessionId);
        executionSubscriptions.remove(sessionId);
    }

    private static boolean add(Map<String, Set<UUID>> subscriptions, String sessionId, UUID targetId) {
        return subscriptions
                .computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet())
                .add(targetId);
    }

    private static boolean remove(Map<String, Set<UUID>> subscriptions, String sessionId, UUID targetId) {
        Set<UUID> targets = subscriptions.get(sessionId);
        if (targets == null) {
            return false;
        }
        boolean removed = targets.remove(targetId);
        if (targets.isEmpty()) {
            subscriptions.remove(sessionId, targets);
        }
        return removed;
    }

    private static boolean contains(Map<String, Set<UUID>> subscriptions, String sessionId, UUID targetId) {
        Set<UUID> targets = subscriptions.get(sessionId);
        return targets != null && targets.contains(targetId);
    }
}
