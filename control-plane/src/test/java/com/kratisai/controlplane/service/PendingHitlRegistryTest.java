package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class PendingHitlRegistryTest {

    private PendingHitlRegistry registry;

    private final UUID executionId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    private EnvironmentSessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(EnvironmentSessionRegistry.class);
        registry = new PendingHitlRegistry(sessionRegistry);
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private ExecutionHitlRequiredResult sampleRequest(UUID forExecutionId, String hitlId, HitlKind kind) {
        return new ExecutionHitlRequiredResult(
                forExecutionId,
                hitlId,
                kind,
                kind == HitlKind.QUESTION ? "Pick a target" : "message",
                kind == HitlKind.APPROVAL ? "echo hello" : null,
                null,
                null,
                null,
                null,
                null,
                kind == HitlKind.QUESTION ? Map.of("type", "object") : null);
    }

    private PendingHitlRegistry.PendingHitl registerSample(String sessionId, String hitlId, HitlKind kind) {
        WebSocketSession session = session(sessionId);
        PendingHitlRegistry.PendingHitl hitl = new PendingHitlRegistry.PendingHitl(
                sampleRequest(executionId, hitlId, kind), session, 42, Instant.now(), teamId);
        registry.register(executionId, hitl);
        return hitl;
    }

    @Test
    void registerAndRemove() {
        PendingHitlRegistry.PendingHitl hitl = registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);

        assertThat(registry.getPending()).containsEntry(executionId, hitl);

        PendingHitlRegistry.PendingHitl removed = registry.remove(executionId);
        assertThat(removed).isSameAs(hitl);
        assertThat(registry.getPending()).doesNotContainKey(executionId);

        assertThat(registry.remove(executionId)).isNull();
    }

    @Test
    void clearAllEmptiesRegistry() {
        registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        registry.clearAll();
        assertThat(registry.getPending()).isEmpty();
    }

    @Test
    void registerKeepsBothKinds() {
        registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        UUID questionExecution = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl question = new PendingHitlRegistry.PendingHitl(
                sampleRequest(questionExecution, "hitl-2", HitlKind.QUESTION),
                session("ws-1"),
                43,
                Instant.now(),
                teamId);
        registry.register(questionExecution, question);

        assertThat(registry.getPending().get(executionId).request().kind()).isEqualTo(HitlKind.APPROVAL);
        assertThat(registry.getPending().get(questionExecution).request().kind())
                .isEqualTo(HitlKind.QUESTION);
        assertThat(registry.getPending().get(questionExecution).request().form())
                .containsEntry("type", "object");
    }

    @Test
    void getExpiredReturnsOnlyOldEntries() {
        PendingHitlRegistry.PendingHitl fresh = registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        registry.getPending()
                .put(
                        executionId,
                        new PendingHitlRegistry.PendingHitl(
                                fresh.request(),
                                fresh.session(),
                                fresh.requestId(),
                                // Well beyond the registry's default timeout.
                                Instant.now().minusSeconds(3600),
                                teamId));

        Map<UUID, PendingHitlRegistry.PendingHitl> expired = registry.getExpired();
        assertThat(expired).containsKey(executionId);

        UUID freshExecution = UUID.randomUUID();
        registry.register(
                freshExecution,
                new PendingHitlRegistry.PendingHitl(
                        sampleRequest(freshExecution, "hitl-2", HitlKind.QUESTION),
                        fresh.session(),
                        44,
                        Instant.now(),
                        teamId));
        assertThat(registry.getExpired()).doesNotContainKey(freshExecution);
    }

    @Test
    void removeExpiredRemovesOnlyExpired() {
        PendingHitlRegistry.PendingHitl fresh = registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        registry.getPending()
                .put(
                        executionId,
                        new PendingHitlRegistry.PendingHitl(
                                fresh.request(),
                                fresh.session(),
                                fresh.requestId(),
                                // Well beyond the registry's default timeout.
                                Instant.now().minusSeconds(3600),
                                teamId));
        UUID freshExecution = UUID.randomUUID();
        registry.register(
                freshExecution,
                new PendingHitlRegistry.PendingHitl(
                        sampleRequest(freshExecution, "hitl-2", HitlKind.QUESTION),
                        fresh.session(),
                        44,
                        Instant.now(),
                        teamId));

        Map<UUID, PendingHitlRegistry.PendingHitl> removed = registry.removeExpired();
        assertThat(removed).containsKey(executionId);
        assertThat(removed).doesNotContainKey(freshExecution);
        assertThat(registry.getPending()).doesNotContainKey(executionId);
        assertThat(registry.getPending()).containsKey(freshExecution);
    }

    @Test
    void removeBySessionRemovesMatchingSession() {
        registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        UUID otherExecution = UUID.randomUUID();
        registry.register(
                otherExecution,
                new PendingHitlRegistry.PendingHitl(
                        sampleRequest(otherExecution, "hitl-2", HitlKind.QUESTION),
                        session("ws-2"),
                        44,
                        Instant.now(),
                        teamId));

        Map<UUID, PendingHitlRegistry.PendingHitl> removed = registry.removeBySession(session("ws-1"));
        assertThat(removed).containsKey(executionId);
        assertThat(removed).doesNotContainKey(otherExecution);
        assertThat(registry.getPending()).containsKey(otherExecution);
    }

    @Test
    void rebindBySessionMovesEntriesToNewSession() {
        registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        WebSocketSession newSession = session("ws-2");

        int count = registry.rebindBySession("ws-1", newSession);
        assertThat(count).isEqualTo(1);
        assertThat(registry.getPending().get(executionId).session()).isSameAs(newSession);
    }

    @Test
    void rebindBySessionIdMovesEntries() {
        registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        WebSocketSession newSession = session("ws-2");
        when(sessionRegistry.getSession("ws-2")).thenReturn(newSession);

        int count = registry.rebindBySession("ws-1", "ws-2");
        assertThat(count).isEqualTo(1);
        assertThat(registry.getPending().get(executionId).session()).isSameAs(newSession);
    }

    @Test
    void registerKeepsConnectionSession() {
        WebSocketSession session = session("ws-3");
        when(sessionRegistry.getSession("ws-3")).thenReturn(session);
        registry.register(
                executionId, sampleRequest(executionId, "hitl-1", HitlKind.QUESTION), session.getId(), 42, teamId);
        assertThat(registry.getPending().get(executionId).session()).isSameAs(session);
        assertThat(registry.getPending().get(executionId).request().kind()).isEqualTo(HitlKind.QUESTION);
    }

    @Test
    void registerBySessionId_unknownSession_throws() {
        assertThatThrownBy(() -> registry.register(
                        executionId, sampleRequest(executionId, "hitl-1", HitlKind.QUESTION), "missing", 42, teamId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rebindBySessionId_unknownSession_throws() {
        registerSample("ws-1", "hitl-1", HitlKind.APPROVAL);
        assertThatThrownBy(() -> registry.rebindBySession("ws-1", "missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }
}
