package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlRequestSnapshot;
import java.time.Instant;
import java.util.List;
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

    private HitlRequestSnapshot sampleRequest(UUID forExecutionId, String actionId, HitlKind kind) {
        return new HitlRequestSnapshot(
                forExecutionId,
                actionId,
                kind,
                kind == HitlKind.QUESTION ? "Pick a target" : "message",
                kind == HitlKind.APPROVAL ? "echo hello" : null,
                null,
                null,
                null,
                null,
                null,
                kind == HitlKind.QUESTION ? Map.of("type", "object") : null,
                null,
                null);
    }

    private PendingHitlRegistry.PendingHitl registerSample(String sessionId, String actionId, HitlKind kind) {
        WebSocketSession session = session(sessionId);
        PendingHitlRegistry.PendingHitl hitl = new PendingHitlRegistry.PendingHitl(
                sampleRequest(executionId, actionId, kind), session, 42, Instant.now(), teamId);
        registry.register(hitl);
        return hitl;
    }

    @Test
    void registerAndRemove() {
        PendingHitlRegistry.PendingHitl hitl = registerSample("ws-1", "action-1", HitlKind.APPROVAL);

        assertThat(registry.find(executionId, "action-1")).isSameAs(hitl);

        PendingHitlRegistry.PendingHitl removed = registry.remove(executionId, "action-1");
        assertThat(removed).isSameAs(hitl);
        assertThat(registry.find(executionId, "action-1")).isNull();

        assertThat(registry.remove(executionId, "action-1")).isNull();
    }

    @Test
    void clearAllEmptiesRegistry() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        registry.clearAll();
        assertThat(registry.find(executionId, "action-1")).isNull();
    }

    @Test
    void registerKeepsBothKinds() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        UUID questionExecution = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl question = new PendingHitlRegistry.PendingHitl(
                sampleRequest(questionExecution, "action-2", HitlKind.QUESTION),
                session("ws-1"),
                43,
                Instant.now(),
                teamId);
        registry.register(question);

        assertThat(registry.find(executionId, "action-1").request().kind()).isEqualTo(HitlKind.APPROVAL);
        assertThat(registry.find(questionExecution, "action-2").request().kind())
                .isEqualTo(HitlKind.QUESTION);
        assertThat(registry.find(questionExecution, "action-2").request().form())
                .containsEntry("type", "object");
    }

    @Test
    void registerKeepsConcurrentRequestsForSameExecution() {
        PendingHitlRegistry.PendingHitl first = registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        PendingHitlRegistry.PendingHitl second = new PendingHitlRegistry.PendingHitl(
                sampleRequest(executionId, "action-2", HitlKind.APPROVAL), session("ws-1"), 43, Instant.now(), teamId);
        registry.register(second);

        assertThat(registry.find(executionId, "action-1")).isSameAs(first);
        assertThat(registry.find(executionId, "action-2")).isSameAs(second);

        assertThat(registry.remove(executionId, "action-1")).isSameAs(first);
        assertThat(registry.find(executionId, "action-2")).isSameAs(second);
    }

    @Test
    void removeAllRemovesEveryRequestForExecution() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        PendingHitlRegistry.PendingHitl second = new PendingHitlRegistry.PendingHitl(
                sampleRequest(executionId, "action-2", HitlKind.APPROVAL), session("ws-1"), 43, Instant.now(), teamId);
        registry.register(second);
        UUID otherExecution = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl other = new PendingHitlRegistry.PendingHitl(
                sampleRequest(otherExecution, "action-3", HitlKind.QUESTION),
                session("ws-2"),
                44,
                Instant.now(),
                teamId);
        registry.register(other);

        List<PendingHitlRegistry.PendingHitl> removed = registry.removeAll(executionId);
        assertThat(removed).hasSize(2);
        assertThat(registry.find(executionId, "action-1")).isNull();
        assertThat(registry.find(executionId, "action-2")).isNull();
        assertThat(registry.find(otherExecution, "action-3")).isSameAs(other);
    }

    @Test
    void removeExpiredReturnsOnlyExpiredRequests() {
        PendingHitlRegistry.PendingHitl fresh = registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        PendingHitlRegistry.PendingHitl expired = new PendingHitlRegistry.PendingHitl(
                sampleRequest(executionId, "action-stale", HitlKind.APPROVAL),
                session("ws-1"),
                43,
                Instant.now().minusSeconds(3600),
                teamId);
        registry.register(expired);

        UUID freshExecution = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl freshOther = new PendingHitlRegistry.PendingHitl(
                sampleRequest(freshExecution, "action-2", HitlKind.QUESTION),
                session("ws-1"),
                44,
                Instant.now(),
                teamId);
        registry.register(freshOther);

        List<PendingHitlRegistry.PendingHitl> removed = registry.removeExpired();
        assertThat(removed).containsExactly(expired);
        assertThat(registry.find(executionId, "action-stale")).isNull();
        assertThat(registry.find(executionId, "action-1")).isSameAs(fresh);
        assertThat(registry.find(freshExecution, "action-2")).isSameAs(freshOther);
    }

    @Test
    void removeBySessionRemovesMatchingSession() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        UUID otherExecution = UUID.randomUUID();
        PendingHitlRegistry.PendingHitl other = new PendingHitlRegistry.PendingHitl(
                sampleRequest(otherExecution, "action-2", HitlKind.QUESTION),
                session("ws-2"),
                44,
                Instant.now(),
                teamId);
        registry.register(other);

        List<PendingHitlRegistry.PendingHitl> removed = registry.removeBySession(session("ws-1"));
        assertThat(removed).extracting(request -> request.request().actionId()).containsExactly("action-1");
        assertThat(registry.find(executionId, "action-1")).isNull();
        assertThat(registry.find(otherExecution, "action-2")).isSameAs(other);
    }

    @Test
    void rebindBySessionMovesEntriesToNewSession() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        WebSocketSession newSession = session("ws-2");

        int count = registry.rebindBySession("ws-1", newSession);
        assertThat(count).isEqualTo(1);
        assertThat(registry.find(executionId, "action-1").session()).isSameAs(newSession);
    }

    @Test
    void rebindBySessionIdMovesEntries() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        WebSocketSession newSession = session("ws-2");
        when(sessionRegistry.getSession("ws-2")).thenReturn(newSession);

        int count = registry.rebindBySession("ws-1", "ws-2");
        assertThat(count).isEqualTo(1);
        assertThat(registry.find(executionId, "action-1").session()).isSameAs(newSession);
    }

    @Test
    void registerKeepsConnectionSession() {
        WebSocketSession session = session("ws-3");
        when(sessionRegistry.getSession("ws-3")).thenReturn(session);
        registry.register(sampleRequest(executionId, "action-1", HitlKind.QUESTION), session.getId(), 42, teamId);
        assertThat(registry.find(executionId, "action-1").session()).isSameAs(session);
        assertThat(registry.find(executionId, "action-1").request().kind()).isEqualTo(HitlKind.QUESTION);
    }

    @Test
    void registerBySessionId_unknownSession_throws() {
        assertThatThrownBy(() -> registry.register(
                        sampleRequest(executionId, "action-1", HitlKind.QUESTION), "missing", 42, teamId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rebindBySessionId_unknownSession_throws() {
        registerSample("ws-1", "action-1", HitlKind.APPROVAL);
        assertThatThrownBy(() -> registry.rebindBySession("ws-1", "missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }
}
