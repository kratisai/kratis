package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.JsonRpcResponse;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.SubscriptionRegistry;
import com.kratisai.controlplane.service.WebSocketDispatch;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class ClientWebSocketHandlerTest {

    @Mock
    private SubscriptionRegistry subscriptionRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClientSessionRegistry sessionRegistry;
    private WebSocketDispatch dispatch;
    private ClientWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry = new ClientSessionRegistry(subscriptionRegistry);
        dispatch = new WebSocketDispatch(objectMapper, sessionRegistry, subscriptionRegistry, Runnable::run);
        handler = new ClientWebSocketHandler(Collections.emptyList(), sessionRegistry, dispatch, objectMapper);
    }

    @Test
    void afterConnectionEstablished_shouldRegisterPendingSession() {
        WebSocketSession session = mockSession();

        handler.afterConnectionEstablished(session);

        assertThat(sessionRegistry.getPendingSessionCount()).isEqualTo(1);
        assertThat(sessionRegistry.getAuthenticatedSessionCount()).isEqualTo(0);
    }

    @Test
    void afterConnectionEstablished_shouldWrapSessionInConcurrentDecorator() {
        WebSocketSession session = mockSession();

        handler.afterConnectionEstablished(session);

        WebSocketSession resolved = sessionRegistry.resolveSafeSession(session);
        assertThat(resolved).isInstanceOf(ConcurrentWebSocketSessionDecorator.class);
        assertThat(resolved).isNotSameAs(session);
    }

    @Test
    void handleTextMessage_delegatesToHandlerWithSessionId() throws Exception {
        handler.afterConnectionEstablished(mockSession());
        ClientRpcHandler<ClientRpcPayload.Ping, ClientPayload.PingResult> mockRpcHandler =
                mock(ClientPingRpcHandler.class);
        when(mockRpcHandler.getMethodName()).thenReturn("test.method");
        when(mockRpcHandler.getPayloadType()).thenReturn(ClientRpcPayload.Ping.class);
        when(mockRpcHandler.handle(any(), any(), any())).thenReturn(Flux.empty());
        ClientWebSocketHandler handlerWithMock =
                new ClientWebSocketHandler(List.of(mockRpcHandler), sessionRegistry, dispatch, objectMapper);

        WebSocketSession session = mockSession();
        JsonRpcInboundRequest request = new JsonRpcInboundRequest("2.0", "test.method", null, 1);
        String payload = objectMapper.writeValueAsString(request);

        handlerWithMock.handleTextMessage(session, new TextMessage(payload));

        verify(mockRpcHandler).handle(eq("ws-1"), eq(1), any());
    }

    @Test
    void afterConnectionClosed_shouldRemoveSession() {
        WebSocketSession session = mockSession();

        handler.afterConnectionEstablished(session);
        sessionRegistry.authenticateSession(session, "user-1");
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(sessionRegistry.getAuthenticatedSessionCount()).isEqualTo(0);
    }

    @Test
    void handleTextMessage_unknownMethod_shouldReturnMethodNotFound() throws Exception {
        WebSocketSession session = mockSession();
        JsonRpcInboundRequest request = new JsonRpcInboundRequest("2.0", "unknown.method", null, 1);
        String payload = objectMapper.writeValueAsString(request);

        handler.handleTextMessage(session, new TextMessage(payload));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonRpcResponse<?> response = objectMapper.readValue(captor.getValue().getPayload(), JsonRpcResponse.class);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(-32601); // METHOD_NOT_FOUND
    }

    @Test
    void handleTextMessage_invalidJson_shouldReturnInternalError() throws Exception {
        WebSocketSession session = mockSession();

        handler.handleTextMessage(session, new TextMessage("invalid json"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonRpcResponse<?> response = objectMapper.readValue(captor.getValue().getPayload(), JsonRpcResponse.class);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(-32603); // INTERNAL_ERROR
    }

    @Test
    void getAuthenticatedSessionCount_shouldReturnRegistryCount() {
        WebSocketSession session = mockSession();

        sessionRegistry.authenticateSession(session, "user-1");

        assertThat(handler.getAuthenticatedSessionCount()).isEqualTo(1);
    }

    @Test
    void getPendingSessionCount_shouldReturnRegistryCount() {
        WebSocketSession session = mockSession();

        handler.afterConnectionEstablished(session);

        assertThat(handler.getPendingSessionCount()).isEqualTo(1);
    }

    private WebSocketSession mockSession() {
        WebSocketSession session =
                mock(WebSocketSession.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
