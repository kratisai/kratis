package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.HitlRequestSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
            HitlRequestSnapshot request, WebSocketSession session, Object requestId, Instant createdAt, UUID teamId) {}

    private record HitlKey(UUID executionId, String actionId) {
        private HitlKey {
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(actionId, "actionId is required");
        }
    }

    private final Map<HitlKey, PendingHitl> pending = new ConcurrentHashMap<>();
    private final EnvironmentSessionRegistry sessionRegistry;

    public PendingHitlRegistry(EnvironmentSessionRegistry sessionRegistry) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry is required");
    }

    public void register(PendingHitl pendingHitl) {
        HitlKey key = keyOf(pendingHitl.request());
        PendingHitl previous = pending.put(key, pendingHitl);
        if (previous != null) {
            logger.warn(
                    "Replaced pending HITL request for execution {} actionId='{}' (kind={})",
                    key.executionId(),
                    key.actionId(),
                    previous.request().kind());
        }
        logger.info(
                "Registered pending HITL request for execution {} (actionId='{}', kind={}, team={}, request id={})",
                key.executionId(),
                key.actionId(),
                pendingHitl.request().kind(),
                pendingHitl.teamId(),
                pendingHitl.requestId());
    }

    public void register(HitlRequestSnapshot request, String sessionId, Object requestId, UUID teamId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null) {
            throw new IllegalStateException("Unknown environment session " + sessionId);
        }
        register(new PendingHitl(request, session, requestId, Instant.now(), teamId));
    }

    public PendingHitl find(UUID executionId, String actionId) {
        return pending.get(new HitlKey(executionId, actionId));
    }

    public PendingHitl remove(UUID executionId, String actionId) {
        PendingHitl removed = pending.remove(new HitlKey(executionId, actionId));
        if (removed != null) {
            logger.info(
                    "Removed pending HITL request for execution {} (actionId='{}', kind={})",
                    executionId,
                    actionId,
                    removed.request().kind());
        } else {
            logger.debug("No pending HITL request found for execution {} actionId='{}'", executionId, actionId);
        }
        return removed;
    }

    /** Removes every pending request for an execution. Normally one; terminate must not leave any behind. */
    public List<PendingHitl> removeAll(UUID executionId) {
        List<PendingHitl> removed = new ArrayList<>();
        pending.forEach((key, value) -> {
            if (key.executionId().equals(executionId) && pending.remove(key, value)) {
                removed.add(value);
            }
        });
        if (!removed.isEmpty()) {
            logger.info("Removed {} pending HITL request(s) for execution {}", removed.size(), executionId);
        }
        return List.copyOf(removed);
    }

    public void clearAll() {
        pending.clear();
    }

    public List<PendingHitl> removeExpired() {
        Instant cutoff = Instant.now().minus(DEFAULT_TIMEOUT);
        List<PendingHitl> removed = new ArrayList<>();
        pending.forEach((key, value) -> {
            if (value.createdAt().isBefore(cutoff) && pending.remove(key, value)) {
                removed.add(value);
            }
        });
        if (!removed.isEmpty()) {
            logger.info("Removed {} expired pending HITL requests", removed.size());
        }
        return List.copyOf(removed);
    }

    public List<PendingHitl> removeBySession(WebSocketSession session) {
        List<PendingHitl> removed = new ArrayList<>();
        pending.forEach((key, request) -> {
            if (request.session().getId().equals(session.getId()) && pending.remove(key, request)) {
                removed.add(request);
                logger.info(
                        "Removed pending HITL request for execution {} (actionId='{}') due to session disconnect",
                        key.executionId(),
                        key.actionId());
            }
        });
        return List.copyOf(removed);
    }

    public int rebindBySession(String oldSessionId, WebSocketSession newSession) {
        int count = 0;
        for (Map.Entry<HitlKey, PendingHitl> entry : pending.entrySet()) {
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

    private static HitlKey keyOf(HitlRequestSnapshot request) {
        return new HitlKey(request.executionId(), request.actionId());
    }
}
