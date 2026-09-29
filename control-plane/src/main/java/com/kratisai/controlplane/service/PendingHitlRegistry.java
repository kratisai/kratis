package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/** Tracks pending HITL requests (command approvals and form questions) awaiting a user answer. */
@Component
public class PendingHitlRegistry {

    private static final Logger logger = LoggerFactory.getLogger(PendingHitlRegistry.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    public record PendingHitl(
            ExecutionHitlRequiredResult request,
            WebSocketSession session,
            Object requestId,
            Instant createdAt,
            UUID teamId) {}

    private final Map<UUID, PendingHitl> pending = new ConcurrentHashMap<>();
    private final EnvironmentSessionRegistry sessionRegistry;

    public PendingHitlRegistry(EnvironmentSessionRegistry sessionRegistry) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry is required");
    }

    public void register(UUID executionId, PendingHitl pendingHitl) {
        pending.put(executionId, pendingHitl);
        logger.info(
                "Registered pending HITL request for execution {} (hitlId='{}', kind={}, team={}, request id={})",
                executionId,
                pendingHitl.request().hitlId(),
                pendingHitl.request().kind(),
                pendingHitl.teamId(),
                pendingHitl.requestId());
    }

    public void register(
            UUID executionId, ExecutionHitlRequiredResult request, String sessionId, Object requestId, UUID teamId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null) {
            throw new IllegalStateException("Unknown environment session " + sessionId);
        }
        register(executionId, new PendingHitl(request, session, requestId, Instant.now(), teamId));
    }

    public PendingHitl remove(UUID executionId) {
        PendingHitl removed = pending.remove(executionId);
        if (removed != null) {
            logger.info(
                    "Removed pending HITL request for execution {} (hitlId='{}', kind={})",
                    executionId,
                    removed.request().hitlId(),
                    removed.request().kind());
        } else {
            logger.debug("No pending HITL request found for execution {}", executionId);
        }
        return removed;
    }

    /** Clear all pending requests. Used by tests to reset state between test cases. */
    public void clearAll() {
        pending.clear();
    }

    public Map<UUID, PendingHitl> getPending() {
        return pending;
    }

    public Map<UUID, PendingHitl> getExpired() {
        return getExpired(DEFAULT_TIMEOUT);
    }

    public Map<UUID, PendingHitl> getExpired(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        return pending.entrySet().stream()
                .filter(entry -> entry.getValue().createdAt().isBefore(cutoff))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<UUID, PendingHitl> removeExpired() {
        return removeExpired(DEFAULT_TIMEOUT);
    }

    public Map<UUID, PendingHitl> removeExpired(Duration timeout) {
        Map<UUID, PendingHitl> expired = getExpired(timeout);
        expired.keySet().forEach(this::remove);
        if (!expired.isEmpty()) {
            logger.info("Removed {} expired pending HITL requests", expired.size());
        }
        return expired;
    }

    public Map<UUID, PendingHitl> removeBySession(WebSocketSession session) {
        Map<UUID, PendingHitl> removed = new ConcurrentHashMap<>();
        pending.forEach((executionId, request) -> {
            if (request.session().getId().equals(session.getId())) {
                PendingHitl removedRequest = pending.remove(executionId);
                if (removedRequest != null) {
                    removed.put(executionId, removedRequest);
                    logger.info(
                            "Removed pending HITL request for execution {} (hitlId='{}') due to session disconnect",
                            executionId,
                            removedRequest.request().hitlId());
                }
            }
        });
        return removed;
    }

    public int rebindBySession(String oldSessionId, WebSocketSession newSession) {
        int count = 0;
        for (Map.Entry<UUID, PendingHitl> entry : pending.entrySet()) {
            PendingHitl request = entry.getValue();
            if (request.session().getId().equals(oldSessionId)) {
                pending.put(
                        entry.getKey(),
                        new PendingHitl(
                                request.request(),
                                newSession,
                                request.requestId(),
                                request.createdAt(),
                                request.teamId()));
                count++;
            }
        }
        if (count > 0) {
            logger.info(
                    "Rebound {} pending HITL requests from session {} to {}", count, oldSessionId, newSession.getId());
        }
        return count;
    }

    public int rebindBySession(String oldSessionId, String newSessionId) {
        WebSocketSession newSession = sessionRegistry.getSession(newSessionId);
        if (newSession == null) {
            throw new IllegalStateException("Unknown environment session " + newSessionId);
        }
        return rebindBySession(oldSessionId, newSession);
    }
}
