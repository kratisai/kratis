package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.SubscriptionRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class ClientSessionRegistryTest {

    @Mock
    private SubscriptionRegistry subscriptionRegistry;

    private ClientSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ClientSessionRegistry(subscriptionRegistry);
    }

    @Test
    void registerPendingSession_shouldTrackPendingSession() {
        WebSocketSession session = mockSession();
        registry.registerPendingSession(session);

        assertThat(registry.getPendingSessionCount()).isEqualTo(1);
        assertThat(registry.getAuthenticatedSessionCount()).isEqualTo(0);
    }

    @Test
    void authenticateSession_shouldMoveFromPendingToAuthenticated() {
        WebSocketSession session = mockSession();

        registry.registerPendingSession(session);
        registry.authenticateSession(session, "user-1");

        assertThat(registry.getPendingSessionCount()).isEqualTo(0);
        assertThat(registry.getAuthenticatedSessionCount()).isEqualTo(1);
        assertThat(registry.getUserId("ws-1")).isEqualTo(Optional.of("user-1"));
    }

    @Test
    void authenticateSessionById_shouldMovePendingSession() {
        WebSocketSession session = mockSession();
        registry.registerPendingSession(session);

        registry.authenticateSession("ws-1", "user-1");

        assertThat(registry.getPendingSessionCount()).isEqualTo(0);
        assertThat(registry.getAuthenticatedSessionCount()).isEqualTo(1);
        assertThat(registry.getUserId("ws-1")).isEqualTo(Optional.of("user-1"));
    }

    @Test
    void authenticateSessionById_unknownSession_throws() {
        assertThatThrownBy(() -> registry.authenticateSession("missing", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void authenticateSession_shouldTrackUserIdMapping() {
        WebSocketSession session = mockSession();

        registry.authenticateSession(session, "user-1");

        assertThat(registry.getUserId("ws-1")).isEqualTo(Optional.of("user-1"));
    }

    @Test
    void removeSession_shouldCleanUpAllMappings() {
        WebSocketSession session = mockSession();

        registry.authenticateSession(session, "user-1");
        registry.removeSession("ws-1");

        assertThat(registry.getAuthenticatedSessionCount()).isEqualTo(0);
        assertThat(registry.getUserId("ws-1")).isEmpty();
    }

    @Test
    void removeSession_pendingSession_shouldRemovePending() {
        WebSocketSession session = mockSession();

        registry.registerPendingSession(session);
        registry.removeSession("ws-1");

        assertThat(registry.getPendingSessionCount()).isEqualTo(0);
    }

    @Test
    void getUserId_unknownSession_shouldReturnEmpty() {
        assertThat(registry.getUserId("unknown")).isEmpty();
    }

    @Test
    void getSessionsForUser_nullUserId_shouldReturnEmptyList() {
        assertThat(registry.getSessionsForUser(null)).isEmpty();
    }

    @Test
    void getSessionsForUser_authenticatedUser_shouldReturnSessions() {
        WebSocketSession session = mockSession();
        UUID userId = UUID.randomUUID();

        registry.authenticateSession(session, userId.toString());

        List<WebSocketSession> sessions = registry.getSessionsForUser(userId);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst()).isEqualTo(session);
    }

    @Test
    void getSessionsForUser_closedSession_shouldNotBeReturned() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(false);
        UUID userId = UUID.randomUUID();

        registry.authenticateSession(session, userId.toString());

        List<WebSocketSession> sessions = registry.getSessionsForUser(userId);
        assertThat(sessions).isEmpty();
    }

    @Test
    void cleanupStaleSessions_shouldRemoveClosedAuthenticatedSessions() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(false);
        registry.authenticateSession(session, "user-1");

        registry.cleanupStaleSessions();

        assertThat(registry.getAuthenticatedSessionCount()).isEqualTo(0);
    }

    @Test
    void cleanupStaleSessions_shouldKeepOpenAuthenticatedSessions() {
        WebSocketSession session = mockSession();
        when(session.isOpen()).thenReturn(true);
        registry.authenticateSession(session, "user-1");

        registry.cleanupStaleSessions();

        assertThat(registry.getAuthenticatedSessionCount()).isEqualTo(1);
    }

    @Test
    void resolveSafeSession_returnsAuthenticatedTrackedInstance() {
        WebSocketSession original = mockSession();
        WebSocketSession decorated =
                mock(WebSocketSession.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(decorated.getId()).thenReturn("ws-1");
        registry.authenticateSession(decorated, "user-1");

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
