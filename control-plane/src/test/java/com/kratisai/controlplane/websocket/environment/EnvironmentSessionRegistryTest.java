package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.event.EnvironmentDisconnectedEvent;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class EnvironmentSessionRegistryTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EnvironmentSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EnvironmentSessionRegistry(eventPublisher);
    }

    @Test
    void registerPendingSession_shouldTrackPendingSession() {
        WebSocketSession session = mockSession();
        registry.registerPendingSession(session);

        assertThat(registry.getPendingSessionCount()).isEqualTo(1);
        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(0);
    }

    @Test
    void registerEnvironmentSession_shouldMoveFromPendingToRegistered() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();

        registry.registerPendingSession(session);
        registry.registerEnvironmentSession(session, envId);

        assertThat(registry.getPendingSessionCount()).isEqualTo(0);
        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(1);
        assertThat(registry.getEnvironmentId("ws-1")).isEqualTo(Optional.of(envId));
    }

    @Test
    void registerEnvironmentSessionById_shouldMovePendingSession() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();
        registry.registerPendingSession(session);

        registry.registerEnvironmentSession("ws-1", envId);

        assertThat(registry.getPendingSessionCount()).isEqualTo(0);
        assertThat(registry.getEnvironmentId("ws-1")).isEqualTo(Optional.of(envId));
        assertThat(registry.getSession("ws-1")).isSameAs(session);
    }

    @Test
    void getSession_returnsPendingSession() {
        WebSocketSession session = mockSession();
        registry.registerPendingSession(session);

        assertThat(registry.getSession("ws-1")).isSameAs(session);
    }

    @Test
    void getSession_unknown_returnsNull() {
        assertThat(registry.getSession("missing")).isNull();
    }

    @Test
    void registerEnvironmentSessionById_unknown_throws() {
        assertThatThrownBy(() -> registry.registerEnvironmentSession("missing", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void replaceSessionForEnvironmentById_usesPendingSession() {
        WebSocketSession oldSession = mockSession();
        UUID envId = UUID.randomUUID();
        registry.registerEnvironmentSession(oldSession, envId);

        WebSocketSession newSession =
                mock(WebSocketSession.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(newSession.getId()).thenReturn("ws-2");
        when(newSession.isOpen()).thenReturn(true);
        registry.registerPendingSession(newSession);

        String oldId = registry.replaceSessionForEnvironment("ws-2", envId);

        assertThat(oldId).isEqualTo("ws-1");
        assertThat(registry.getSession("ws-2")).isSameAs(newSession);
        assertThat(registry.getEnvironmentId("ws-2")).contains(envId);
    }

    @Test
    void registerEnvironmentSession_shouldTrackEnvironmentMapping() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();

        registry.registerEnvironmentSession(session, envId);

        assertThat(registry.getEnvironmentId("ws-1")).isEqualTo(Optional.of(envId));
    }

    @Test
    void removeSession_shouldCleanUpAllMappings() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();

        registry.registerEnvironmentSession(session, envId);
        registry.removeSession("ws-1");

        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(0);
        assertThat(registry.getEnvironmentId("ws-1")).isEmpty();
    }

    @Test
    void removeSession_environmentSession_shouldPublishDisconnectEvent() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();

        registry.registerEnvironmentSession(session, envId);
        registry.removeSession("ws-1");

        ArgumentCaptor<EnvironmentDisconnectedEvent> captor =
                ArgumentCaptor.forClass(EnvironmentDisconnectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().environmentId()).isEqualTo(envId);
    }

    @Test
    void removeSession_pendingSession_shouldNotPublishEvent() {
        WebSocketSession session = mockSession();

        registry.registerPendingSession(session);
        registry.removeSession("ws-1");

        assertThat(registry.getPendingSessionCount()).isEqualTo(0);
        // No event should be published for pending sessions
    }

    @Test
    void getEnvironmentId_unknownSession_shouldReturnEmpty() {
        assertThat(registry.getEnvironmentId("unknown")).isEmpty();
    }

    @Test
    void getSessionForEnvironment_nullEnvironmentId_shouldReturnNull() {
        assertThat(registry.getSessionForEnvironment(null)).isNull();
    }

    @Test
    void getSessionForEnvironment_unknownEnvironment_shouldReturnNull() {
        assertThat(registry.getSessionForEnvironment(UUID.randomUUID())).isNull();
    }

    @Test
    void getSessionForEnvironment_registeredEnvironment_shouldReturnSession() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(true);
        UUID envId = UUID.randomUUID();

        registry.registerEnvironmentSession(session, envId);

        assertThat(registry.getSessionForEnvironment(envId)).isEqualTo(session);
    }

    @Test
    void getSessionForEnvironment_closedSession_shouldReturnNull() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(false);
        UUID envId = UUID.randomUUID();

        registry.registerEnvironmentSession(session, envId);

        assertThat(registry.getSessionForEnvironment(envId)).isNull();
    }

    @Test
    void cleanupStaleSessions_shouldRemoveClosedEnvironmentSessions() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(false);
        UUID envId = UUID.randomUUID();
        registry.registerEnvironmentSession(session, envId);

        registry.cleanupStaleSessions();

        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(0);
        // Should publish disconnect event for unexpectedly closed session
        ArgumentCaptor<EnvironmentDisconnectedEvent> captor =
                ArgumentCaptor.forClass(EnvironmentDisconnectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().environmentId()).isEqualTo(envId);
    }

    @Test
    void cleanupStaleSessions_shouldKeepOpenEnvironmentSessions() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(true);
        UUID envId = UUID.randomUUID();
        registry.registerEnvironmentSession(session, envId);

        registry.cleanupStaleSessions();

        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(1);
    }

    @Test
    void replaceSessionForEnvironment_shouldReplaceExistingSession() throws IOException {
        WebSocketSession oldSession = mockSession();
        when(oldSession.getId()).thenReturn("ws-old");
        when(oldSession.isOpen()).thenReturn(true);
        UUID envId = UUID.randomUUID();
        registry.registerEnvironmentSession(oldSession, envId);

        WebSocketSession newSession = mockSession();
        when(newSession.getId()).thenReturn("ws-new");
        String oldSessionId = registry.replaceSessionForEnvironment(newSession, envId);

        assertThat(oldSessionId).isEqualTo("ws-old");
        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(1);
        assertThat(registry.getSessionForEnvironment(envId)).isEqualTo(newSession);
        assertThat(registry.getEnvironmentId("ws-new")).isEqualTo(Optional.of(envId));
        assertThat(registry.getEnvironmentId("ws-old")).isEmpty();
        verify(oldSession).close();
    }

    @Test
    void replaceSessionForEnvironment_withNoExistingSession_shouldReturnNull() {
        WebSocketSession newSession = mockSession();
        when(newSession.getId()).thenReturn("ws-new");
        UUID envId = UUID.randomUUID();

        String oldSessionId = registry.replaceSessionForEnvironment(newSession, envId);

        assertThat(oldSessionId).isNull();
        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(1);
        assertThat(registry.getSessionForEnvironment(envId)).isEqualTo(newSession);
    }

    @Test
    void replaceSessionForEnvironment_withClosedOldSession_shouldNotThrow() throws IOException {
        WebSocketSession oldSession = mockSession();
        when(oldSession.getId()).thenReturn("ws-old");
        when(oldSession.isOpen()).thenReturn(false);
        UUID envId = UUID.randomUUID();
        registry.registerEnvironmentSession(oldSession, envId);

        WebSocketSession newSession = mockSession();
        when(newSession.getId()).thenReturn("ws-new");
        String oldSessionId = registry.replaceSessionForEnvironment(newSession, envId);

        assertThat(oldSessionId).isEqualTo("ws-old");
        assertThat(registry.getEnvironmentSessionCount()).isEqualTo(1);
        verify(oldSession, never()).close();
    }

    @Test
    void resolveSafeSession_returnsRegisteredTrackedInstance() {
        WebSocketSession original = mockSession();
        WebSocketSession decorated =
                mock(WebSocketSession.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(decorated.getId()).thenReturn("ws-1");
        registry.registerEnvironmentSession(decorated, UUID.randomUUID());

        WebSocketSession resolved = registry.resolveSafeSession(original);

        assertThat(resolved).isSameAs(decorated);
    }

    @Test
    void resolveSafeSession_returnsPendingTrackedInstance() {
        WebSocketSession original = mockSession();
        WebSocketSession decorated =
                mock(WebSocketSession.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(decorated.getId()).thenReturn("ws-1");
        registry.registerPendingSession(decorated);

        WebSocketSession resolved = registry.resolveSafeSession(original);

        assertThat(resolved).isSameAs(decorated);
    }

    @Test
    void resolveSafeSession_fallsBackToRawSessionWhenUntracked() {
        WebSocketSession untracked = mockSession();

        WebSocketSession resolved = registry.resolveSafeSession(untracked);

        assertThat(resolved).isSameAs(untracked);
    }

    private WebSocketSession mockSession() {
        WebSocketSession session =
                mock(WebSocketSession.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
