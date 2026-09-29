package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.JsonRpcResponse;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import com.kratisai.controlplane.service.WebSocketDispatch;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class EnvironmentWebSocketHandlerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EnvironmentSessionRegistry sessionRegistry;
    private WebSocketDispatch dispatch;
    private EnvironmentWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry = new EnvironmentSessionRegistry(eventPublisher);
        dispatch = new WebSocketDispatch(objectMapper, null, null, Runnable::run);
        handler = new EnvironmentWebSocketHandler(
                Collections.emptyList(),
                sessionRegistry,
                pendingHitlRegistry,
                environmentRpcClient,
                dispatch,
                objectMapper,
                Runnable::run);
    }

    @Test
    void afterConnectionEstablished_shouldRegisterPendingSession() {
        WebSocketSession session = mockSession();

        handler.afterConnectionEstablished(session);

        assertThat(sessionRegistry.getPendingSessionCount()).isEqualTo(1);
        assertThat(sessionRegistry.getEnvironmentSessionCount()).isEqualTo(0);
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
        EnvironmentRpcHandler<EnvironmentRpcPayload.Heartbeat, EnvironmentResponsePayload.EnvironmentHeartbeatResult>
                mockRpcHandler = mock(EnvironmentHeartbeatRpcHandler.class);
        when(mockRpcHandler.getMethodName()).thenReturn("env.test");
        when(mockRpcHandler.getPayloadType()).thenReturn(EnvironmentRpcPayload.Heartbeat.class);
        when(mockRpcHandler.handle(any(), any(), any())).thenReturn(Flux.empty());
        EnvironmentWebSocketHandler handlerWithMock = new EnvironmentWebSocketHandler(
                List.of(mockRpcHandler),
                sessionRegistry,
                pendingHitlRegistry,
                environmentRpcClient,
                dispatch,
                objectMapper,
                Runnable::run);

        WebSocketSession session = mockSession();
        JsonRpcInboundRequest request = new JsonRpcInboundRequest("2.0", "env.test", null, 1);
        String payload = objectMapper.writeValueAsString(request);

        handlerWithMock.handleTextMessage(session, new TextMessage(payload));

        verify(mockRpcHandler).handle(eq("ws-1"), eq(1), any());
    }

    @Test
    void handleTextMessage_processingIsDeferredToWorkerPoolInOrder() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Thread> processingThread = new AtomicReference<>();
            List<String> processed = new CopyOnWriteArrayList<>();
            CountDownLatch release = new CountDownLatch(1);
            EnvironmentRpcHandler<
                            EnvironmentRpcPayload.Heartbeat, EnvironmentResponsePayload.EnvironmentHeartbeatResult>
                    mockRpcHandler = mock(EnvironmentHeartbeatRpcHandler.class);
            when(mockRpcHandler.getMethodName()).thenReturn("env.test");
            when(mockRpcHandler.getPayloadType()).thenReturn(EnvironmentRpcPayload.Heartbeat.class);
            when(mockRpcHandler.handle(any(), any(), any())).thenAnswer(invocation -> {
                processingThread.set(Thread.currentThread());
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout waiting for release latch");
                }
                processed.add("handled");
                return Flux.empty();
            });
            EnvironmentWebSocketHandler asyncHandler = new EnvironmentWebSocketHandler(
                    List.of(mockRpcHandler),
                    sessionRegistry,
                    pendingHitlRegistry,
                    environmentRpcClient,
                    dispatch,
                    objectMapper,
                    worker);

            WebSocketSession session = mockSession();
            handler.afterConnectionEstablished(session);
            JsonRpcInboundRequest request = new JsonRpcInboundRequest("2.0", "env.test", null, 1);
            String payload = objectMapper.writeValueAsString(request);

            asyncHandler.handleTextMessage(session, new TextMessage(payload));

            // Handler still blocked on the worker pool → the reader thread was freed.
            assertThat(processed).isEmpty();
            release.countDown();
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(processed)
                    .hasSize(1));
            assertThat(processingThread.get()).isNotSameAs(Thread.currentThread());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void afterConnectionClosed_shouldRemoveSession() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();

        handler.afterConnectionEstablished(session);
        sessionRegistry.registerEnvironmentSession(session, envId);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(sessionRegistry.getEnvironmentSessionCount()).isEqualTo(0);
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
    void handleTextMessage_withRegisteredHandler_shouldDelegateToHandler() throws Exception {
        EnvironmentRpcHandler<EnvironmentRpcPayload.Heartbeat, EnvironmentResponsePayload.EnvironmentHeartbeatResult>
                mockRpcHandler = mock(EnvironmentHeartbeatRpcHandler.class);
        when(mockRpcHandler.getMethodName()).thenReturn("env.test");
        when(mockRpcHandler.getPayloadType()).thenReturn(EnvironmentRpcPayload.Heartbeat.class);
        when(mockRpcHandler.handle(any(), any(), any())).thenReturn(Flux.empty());
        EnvironmentWebSocketHandler handlerWithMock = new EnvironmentWebSocketHandler(
                List.of(mockRpcHandler),
                sessionRegistry,
                pendingHitlRegistry,
                environmentRpcClient,
                dispatch,
                objectMapper,
                Runnable::run);

        WebSocketSession session = mockSession();
        JsonRpcInboundRequest request = new JsonRpcInboundRequest("2.0", "env.test", null, 1);
        String payload = objectMapper.writeValueAsString(request);

        handlerWithMock.handleTextMessage(session, new TextMessage(payload));

        verify(mockRpcHandler).handle(any(), any(), any());
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
    void getEnvironmentSessionCount_shouldReturnRegistryCount() {
        WebSocketSession session = mockSession();
        UUID envId = UUID.randomUUID();

        sessionRegistry.registerEnvironmentSession(session, envId);

        assertThat(handler.getEnvironmentSessionCount()).isEqualTo(1);
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
