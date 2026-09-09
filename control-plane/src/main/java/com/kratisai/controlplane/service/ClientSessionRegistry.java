package com.kratisai.controlplane.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Manages client (web-UI) WebSocket session lifecycle: authentication tracking, pending session
 * timeout, and session cleanup.
 *
 * <p>This registry is dedicated to client-side sessions. Environment (sidecar) sessions are managed
 * by {@link EnvironmentSessionRegistry}.
 */
@Component
public class ClientSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ClientSessionRegistry.class);

    /**
     * Maximum time (in seconds) a session can remain unauthenticated before being closed.
     */
    private static final long AUTH_TIMEOUT_SECONDS = 30;

    // Map of sessionId -> WebSocketSession for authenticated client sessions
    private final Map<String, WebSocketSession> authenticatedSessions = new ConcurrentHashMap<>();

    // Map of sessionId -> userId for authenticated client sessions
    private final Map<String, String> sessionUserIds = new ConcurrentHashMap<>();

    // Map of sessionId -> PendingSession for unauthenticated sessions (for timeout clean-up)
    private final Map<String, PendingSession> pendingAuthSessions = new ConcurrentHashMap<>();

    private final SubscriptionRegistry subscriptionRegistry;

    public ClientSessionRegistry(SubscriptionRegistry subscriptionRegistry) {
        this.subscriptionRegistry = subscriptionRegistry;
    }

    /** Tracks a pending (unauthenticated) session with its connection time. */
    public record PendingSession(WebSocketSession session, Instant connectedAt) {}

    /** Register a new pending (unauthenticated) session. */
    public void registerPendingSession(WebSocketSession session) {
        pendingAuthSessions.put(session.getId(), new PendingSession(session, Instant.now()));
    }

    /** Authenticate a session. Moves it from pending to authenticated. */
    public void authenticateSession(WebSocketSession session, String userId) {
        pendingAuthSessions.remove(session.getId());
        authenticatedSessions.put(session.getId(), session);
        sessionUserIds.put(session.getId(), userId);
    }

    public void authenticateSession(String sessionId, String userId) {
        PendingSession pending = pendingAuthSessions.remove(sessionId);
        WebSocketSession session = pending != null ? pending.session() : authenticatedSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Unknown client session " + sessionId);
        }
        authenticateSession(session, userId);
    }

    /** Remove a client session (both authenticated and pending). */
    public void removeSession(String wsSessionId) {
        authenticatedSessions.remove(wsSessionId);
        sessionUserIds.remove(wsSessionId);
        pendingAuthSessions.remove(wsSessionId);
        subscriptionRegistry.removeSession(wsSessionId);
    }

    /** Get the authenticated user ID for a session. */
    public Optional<String> getUserId(String wsSessionId) {
        return Optional.ofNullable(sessionUserIds.get(wsSessionId));
    }

    public WebSocketSession getSession(String sessionId) {
        return authenticatedSessions.get(sessionId);
    }

    /**
     * Container callbacks pass the raw session; return the {@code ConcurrentWebSocketSessionDecorator}
     * registered at connect (or {@code raw} if untracked).
     */
    public WebSocketSession resolveSafeSession(WebSocketSession raw) {
        WebSocketSession authenticated = authenticatedSessions.get(raw.getId());
        if (authenticated != null) {
            return authenticated;
        }
        PendingSession pending = pendingAuthSessions.get(raw.getId());
        if (pending != null) {
            return pending.session();
        }
        return raw;
    }

    /** Get the number of currently authenticated client sessions. */
    public int getAuthenticatedSessionCount() {
        return authenticatedSessions.size();
    }

    /** Get the number of pending (unauthenticated) client sessions. */
    public int getPendingSessionCount() {
        return pendingAuthSessions.size();
    }

    /** Get all authenticated sessions for a given user ID. */
    public List<WebSocketSession> getSessionsForUser(UUID userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String userIdStr = userId.toString();
        return sessionUserIds.entrySet().stream()
                .filter(entry -> userIdStr.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .map(authenticatedSessions::get)
                .filter(session -> session != null && session.isOpen())
                .collect(Collectors.toList());
    }

    /** Clear all session data. Used by tests to reset state between test cases. */
    public void clearAll() {
        authenticatedSessions.clear();
        sessionUserIds.clear();
        pendingAuthSessions.clear();
    }

    /** Clean up stale pending sessions that have exceeded the authentication timeout. */
    public void cleanupStaleSessions() {
        pendingAuthSessions.forEach(this::closeIfElapsed);

        // Defensive cleanup: remove any authenticated sessions that have been closed unexpectedly
        // (e.g., due to network drops where afterConnectionClosed was not triggered)
        authenticatedSessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().isOpen()) {
                String sessionId = entry.getKey();
                logger.info("Cleaning up unexpectedly closed client session {}", sessionId);
                sessionUserIds.remove(sessionId);
                subscriptionRegistry.removeSession(sessionId);
                return true;
            }
            return false;
        });
    }

    private void closeIfElapsed(String sessionId, PendingSession pendingSession) {
        Instant now = Instant.now();
        long elapsedSeconds =
                now.getEpochSecond() - pendingSession.connectedAt().getEpochSecond();
        if (elapsedSeconds > AUTH_TIMEOUT_SECONDS) {
            logger.info("Closing unauthenticated client session {} (idle for {} seconds)", sessionId, elapsedSeconds);
            pendingAuthSessions.remove(sessionId);
            try {
                pendingSession.session().close(CloseStatus.POLICY_VIOLATION.withReason("Authentication timeout"));
            } catch (IOException e) {
                logger.warn("Failed to close stale client session {}: {}", sessionId, e.getMessage());
            }
        }
    }
}
