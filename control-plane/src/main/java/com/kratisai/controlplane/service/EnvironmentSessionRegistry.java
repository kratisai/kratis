package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.event.EnvironmentDisconnectedEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Manages environment (sidecar) WebSocket session lifecycle: registration, environment ID tracking,
 * pending session timeout, and session cleanup.
 */
@Component
public class EnvironmentSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentSessionRegistry.class);

    /**
     * Maximum time (in seconds) an environment session can remain unauthenticated (unregistered)
     * before being closed.
     */
    private static final long AUTH_TIMEOUT_SECONDS = 30;

    // Map of sessionId -> WebSocketSession for authenticated environment sessions
    private final Map<String, WebSocketSession> environmentSessions = new ConcurrentHashMap<>();

    // Map of sessionId -> environmentId for authenticated environment sessions
    private final Map<String, UUID> sessionEnvironmentIds = new ConcurrentHashMap<>();

    // Reverse map: environmentId -> sessionId for O(1) lookups by environment ID
    private final Map<UUID, String> environmentSessionIds = new ConcurrentHashMap<>();

    // Map of sessionId -> PendingEnvironmentSession for unauthenticated sessions (for timeout
    // clean-up)
    private final Map<String, PendingEnvironmentSession> pendingSessions = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentSessionRegistry(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Tracks a pending (unregistered) environment session with its connection time. */
    public record PendingEnvironmentSession(WebSocketSession session, Instant connectedAt) {}

    /** Register a new pending (unregistered) environment session. */
    public void registerPendingSession(WebSocketSession session) {
        pendingSessions.put(session.getId(), new PendingEnvironmentSession(session, Instant.now()));
    }

    /**
     * Register an environment session. Moves it from pending to authenticated environment.
     *
     * @param session the WebSocket session
     * @param environmentId the environment ID to associate with this session
     */
    public void registerEnvironmentSession(WebSocketSession session, UUID environmentId) {
        pendingSessions.remove(session.getId());
        environmentSessions.put(session.getId(), session);
        sessionEnvironmentIds.put(session.getId(), environmentId);
        environmentSessionIds.put(environmentId, session.getId());
    }

    public void registerEnvironmentSession(String sessionId, UUID environmentId) {
        registerEnvironmentSession(requireSession(sessionId), environmentId);
    }

    public WebSocketSession getSession(String sessionId) {
        WebSocketSession registered = environmentSessions.get(sessionId);
        if (registered != null) {
            return registered;
        }
        PendingEnvironmentSession pending = pendingSessions.get(sessionId);
        return pending != null ? pending.session() : null;
    }

    private WebSocketSession requireSession(String sessionId) {
        WebSocketSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalStateException("Unknown environment session " + sessionId);
        }
        return session;
    }

    /**
     * Remove a session (both authenticated and pending). Publishes EnvironmentDisconnectedEvent if
     * the session was associated with an environment.
     *
     * @param wsSessionId the WebSocket session ID to remove
     */
    public void removeSession(String wsSessionId) {
        environmentSessions.remove(wsSessionId);
        pendingSessions.remove(wsSessionId);

        UUID envId = sessionEnvironmentIds.remove(wsSessionId);
        if (envId != null) {
            environmentSessionIds.remove(envId);
            logger.info("Environment {} session closed. Publishing disconnection event.", envId);
            eventPublisher.publishEvent(new EnvironmentDisconnectedEvent(envId));
        }
    }

    /**
     * Get the environment ID for a session.
     *
     * @param wsSessionId the WebSocket session ID
     * @return the environment ID if found
     */
    public Optional<UUID> getEnvironmentId(String wsSessionId) {
        return Optional.ofNullable(sessionEnvironmentIds.get(wsSessionId));
    }

    /**
     * Get the WebSocket session for an environment ID.
     *
     * @param environmentId the environment ID
     * @return the WebSocket session if found and open, null otherwise
     */
    public WebSocketSession getSessionForEnvironment(UUID environmentId) {
        if (environmentId == null) {
            return null;
        }
        String sessionId = environmentSessionIds.get(environmentId);
        if (sessionId == null) {
            return null;
        }
        WebSocketSession session = environmentSessions.get(sessionId);
        if (session != null && session.isOpen()) {
            return session;
        }
        return null;
    }

    /** See {@link ClientSessionRegistry#resolveSafeSession}. */
    public WebSocketSession resolveSafeSession(WebSocketSession raw) {
        WebSocketSession registered = environmentSessions.get(raw.getId());
        if (registered != null) {
            return registered;
        }
        PendingEnvironmentSession pending = pendingSessions.get(raw.getId());
        if (pending != null) {
            return pending.session();
        }
        return raw;
    }

    public String replaceSessionForEnvironment(String sessionId, UUID environmentId) {
        return replaceSessionForEnvironment(requireSession(sessionId), environmentId);
    }

    /**
     * Replace the session for an environment. Closes the old session if it exists and registers the
     * new session.
     *
     * @param newSession the new WebSocket session
     * @param environmentId the environment ID
     * @return the old session ID if it existed, null otherwise
     */
    public String replaceSessionForEnvironment(WebSocketSession newSession, UUID environmentId) {
        String oldSessionId = environmentSessionIds.get(environmentId);
        if (oldSessionId != null) {
            WebSocketSession oldSession = environmentSessions.get(oldSessionId);
            if (oldSession != null && oldSession.isOpen()) {
                try {
                    oldSession.close();
                } catch (Exception e) {
                    logger.warn("Failed to close old session {}", oldSessionId, e);
                }
            }
            environmentSessions.remove(oldSessionId);
            sessionEnvironmentIds.remove(oldSessionId);
        }

        registerEnvironmentSession(newSession, environmentId);
        return oldSessionId;
    }

    /** Get the number of currently registered environment sessions. */
    public int getEnvironmentSessionCount() {
        return environmentSessions.size();
    }

    /** Get the number of pending (unregistered) environment sessions. */
    public int getPendingSessionCount() {
        return pendingSessions.size();
    }

    /** Clear all session data. Used by tests to reset state between test cases. */
    public void clearAll() {
        environmentSessions.clear();
        sessionEnvironmentIds.clear();
        environmentSessionIds.clear();
        pendingSessions.clear();
    }

    /** Clean up stale pending sessions that have exceeded the authentication timeout. */
    public void cleanupStaleSessions() {
        pendingSessions.forEach(this::closeIfElapsed);

        // Defensive cleanup: remove any environment sessions that have been closed unexpectedly
        // (e.g., due to network drops where afterConnectionClosed was not triggered)
        environmentSessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().isOpen()) {
                String sessionId = entry.getKey();
                logger.info("Cleaning up unexpectedly closed environment session {}", sessionId);
                UUID envId = sessionEnvironmentIds.remove(sessionId);
                if (envId != null) {
                    environmentSessionIds.remove(envId);
                    logger.info(
                            "Environment {} session was unexpectedly closed. Publishing disconnection event.", envId);
                    eventPublisher.publishEvent(new EnvironmentDisconnectedEvent(envId));
                }
                return true;
            }
            return false;
        });
    }

    private void closeIfElapsed(String sessionId, PendingEnvironmentSession pendingSession) {
        Instant now = Instant.now();
        long elapsedSeconds =
                now.getEpochSecond() - pendingSession.connectedAt().getEpochSecond();
        if (elapsedSeconds > AUTH_TIMEOUT_SECONDS) {
            logger.info("Closing unregistered environment session {} (idle for {} seconds)", sessionId, elapsedSeconds);
            pendingSessions.remove(sessionId);
            try {
                pendingSession.session().close(CloseStatus.POLICY_VIOLATION.withReason("Registration timeout"));
            } catch (IOException e) {
                logger.warn("Failed to close stale environment session {}: {}", sessionId, e.getMessage());
            }
        }
    }
}
