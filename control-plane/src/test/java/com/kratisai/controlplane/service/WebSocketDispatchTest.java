package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.model.event.UserEntityType;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class WebSocketDispatchTest {

    @Mock
    private ClientSessionRegistry sessionRegistry;

    @Mock
    private SubscriptionRegistry subscriptionRegistry;

    @Mock
    private WebSocketSession mockSession;

    private WebSocketDispatch dispatch;

    @BeforeEach
    void setUp() {
        lenient().when(mockSession.getId()).thenReturn("session-1");
        dispatch = new WebSocketDispatch(new ObjectMapper(), sessionRegistry, subscriptionRegistry, Runnable::run);
    }

    @Test
    void streamClientResponses_serializesPayloadAndRequestId() throws Exception {
        UUID teamId = UUID.randomUUID();
        ClientPayload.TeamEntityChangedResult payload =
                new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.CREDENTIALS);

        dispatch.streamClientResponses(mockSession, 42, Flux.just(payload));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(mockSession).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();
        assertThat(json).contains("\"result\"");
        assertThat(json).contains("team_entity_changed");
        assertThat(json).contains("\"id\":42");
    }

    @Test
    void streamClientResponses_mapsRpcError() throws Exception {
        dispatch.streamClientResponses(
                mockSession, 4, Flux.error(new RpcErrorException(JsonRpcError.InvalidParams("bad"))));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(mockSession).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"code\":-32602");
        assertThat(captor.getValue().getPayload()).contains("\"id\":4");
    }

    @Test
    void broadcastNotificationToTeam_sendsToOpenSubscribedSessionsWithoutId() throws Exception {
        UUID teamId = UUID.randomUUID();
        String sessionId = "session-1";
        doReturn(Set.of(sessionId)).when(subscriptionRegistry).getSubscribers(teamId);
        doReturn(mockSession).when(sessionRegistry).getSession(sessionId);
        doReturn(true).when(mockSession).isOpen();

        dispatch.broadcastNotificationToTeam(
                teamId, new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.CREDENTIALS));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(mockSession).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload())
                .contains("team_entity_changed")
                .doesNotContain("\"id\"");
    }

    @Test
    void broadcastNotificationToTeam_skipsClosedSessions() throws Exception {
        UUID teamId = UUID.randomUUID();
        String sessionId = "session-1";
        doReturn(Set.of(sessionId)).when(subscriptionRegistry).getSubscribers(teamId);
        doReturn(mockSession).when(sessionRegistry).getSession(sessionId);
        doReturn(false).when(mockSession).isOpen();

        dispatch.broadcastNotificationToTeam(
                teamId, new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.REPOSITORIES));

        verify(mockSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastNotificationToTeam_noSubscribers_isDroppedWithoutError() {
        UUID teamId = UUID.randomUUID();
        doReturn(Set.of()).when(subscriptionRegistry).getSubscribers(teamId);

        dispatch.broadcastNotificationToTeam(
                teamId, new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.REPOSITORIES));

        verify(sessionRegistry, never()).getSession(any());
    }

    @Test
    void broadcastNotificationToTeam_sendFailureLoggedNotPropagatedToCaller() throws Exception {
        UUID teamId = UUID.randomUUID();
        String failingSessionId = "session-1";
        doReturn(Set.of(failingSessionId)).when(subscriptionRegistry).getSubscribers(teamId);
        doReturn(mockSession).when(sessionRegistry).getSession(failingSessionId);
        doReturn(true).when(mockSession).isOpen();
        doThrow(new IOException("Broken pipe")).when(mockSession).sendMessage(any());

        dispatch.broadcastNotificationToTeam(
                teamId, new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.CHATS));

        verify(mockSession).sendMessage(any(TextMessage.class));
        // The failed session's serial executor is pruned so it is not retried forever.
        assertThat(dispatch.getBroadcastExecutorCount()).isZero();
    }

    @Test
    void broadcastNotificationToTeam_preservesOrderPerClientSession() {
        UUID teamId = UUID.randomUUID();
        String sessionId = "session-1";
        doReturn(Set.of(sessionId)).when(subscriptionRegistry).getSubscribers(teamId);
        doReturn(mockSession).when(sessionRegistry).getSession(sessionId);
        doReturn(true).when(mockSession).isOpen();

        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            WebSocketDispatch asyncDispatch =
                    new WebSocketDispatch(new ObjectMapper(), sessionRegistry, subscriptionRegistry, worker);

            asyncDispatch.broadcastNotificationToTeam(
                    teamId, new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.CREDENTIALS));
            asyncDispatch.broadcastNotificationToTeam(
                    teamId, new ClientPayload.TeamEntityChangedResult(teamId, TeamEntityType.REPOSITORIES));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            org.awaitility.Awaitility.await()
                    .atMost(5, java.util.concurrent.TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            verify(mockSession, org.mockito.Mockito.times(2)).sendMessage(captor.capture()));
            List<TextMessage> messages = captor.getAllValues();
            assertThat(messages.get(0).getPayload()).contains("CREDENTIALS");
            assertThat(messages.get(1).getPayload()).contains("REPOSITORIES");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void broadcastNotificationToUser_sendsToUserSessions() throws Exception {
        UUID userId = UUID.randomUUID();
        when(sessionRegistry.getSessionsForUser(userId)).thenReturn(List.of(mockSession));
        doReturn(true).when(mockSession).isOpen();

        dispatch.broadcastNotificationToUser(
                userId, new ClientPayload.UserEntityChangedResult(userId, UserEntityType.TEAMS));

        verify(mockSession).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendRequest_buildsTypedRequestEnvelope() throws Exception {
        WebSocketSession envSession = mock(WebSocketSession.class);

        dispatch.sendRequest(envSession, new EnvironmentRpcPayload.Exec("ls", true, "exec-1"), "e1");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(envSession).sendMessage(captor.capture());
        String json = captor.getValue().getPayload();
        assertThat(json).contains("\"method\":\"env.exec\"");
        assertThat(json).contains("\"id\":\"e1\"");
        assertThat(json).contains("\"command\":\"ls\"");
    }

    @Test
    void sendRequest_notificationOmitsId() throws Exception {
        WebSocketSession envSession = mock(WebSocketSession.class);

        dispatch.sendRequest(
                envSession, new EnvironmentRpcPayload.Checkout("https://example.com/repo.git", "main", null, "exec-1"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(envSession).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("env.checkout").doesNotContain("\"id\"");
    }

    @Test
    void streamEnvironmentResponses_sendsPayload() throws Exception {
        WebSocketSession envSession = mock(WebSocketSession.class);
        dispatch.streamEnvironmentResponses(
                envSession, 8, Flux.just(EnvironmentResponsePayload.HitlResult.approved("ok")));

        verify(envSession).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendFailure_throwsWebSocketSendException() throws Exception {
        doThrow(new IOException("Broken pipe")).when(mockSession).sendMessage(any());
        when(mockSession.getId()).thenReturn("ws-1");

        assertThatThrownBy(() ->
                        dispatch.sendRequest(mockSession, new EnvironmentRpcPayload.Exec("x", false, "exec-1"), "1"))
                .isInstanceOf(WebSocketSendException.class)
                .hasMessageContaining("ws-1");
    }
}
