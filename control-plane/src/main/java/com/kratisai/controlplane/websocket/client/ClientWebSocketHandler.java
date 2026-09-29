package com.kratisai.controlplane.websocket.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.config.WebSocketConfig;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.WebSocketDispatch;
import com.kratisai.controlplane.websocket.JsonRpcInbound;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;

/**
 * Client ({@code /ws/client}) JSON-RPC handler. Sessions are wrapped in {@link
 * ConcurrentWebSocketSessionDecorator} at connect so concurrent writers share one ordered send
 * path — Tomcat sessions are not thread-safe.
 */
@Component
public class ClientWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClientWebSocketHandler.class);

    private static final long CLEANUP_INTERVAL_SECONDS = 10;

    private final ObjectMapper objectMapper;

    @SuppressWarnings("rawtypes")
    private final Map<String, ClientRpcHandler> handlers = new ConcurrentHashMap<>();

    private final ClientSessionRegistry sessionRegistry;
    private final WebSocketDispatch dispatch;
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "client-ws-cleanup");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("rawtypes")
    public ClientWebSocketHandler(
            List<ClientRpcHandler> rpcHandlers,
            ClientSessionRegistry sessionRegistry,
            WebSocketDispatch dispatch,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.sessionRegistry = sessionRegistry;
        this.dispatch = dispatch;
        for (ClientRpcHandler handler : rpcHandlers) {
            this.handlers.put(handler.getMethodName(), handler);
        }

        cleanupScheduler.scheduleAtFixedRate(
                sessionRegistry::cleanupStaleSessions,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        logger.info("Client WebSocket connection established: {}", session.getId());
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, WebSocketConfig.CONCURRENT_SEND_TIME_LIMIT_MS, WebSocketConfig.CONCURRENT_SEND_BUFFER_SIZE);
        sessionRegistry.registerPendingSession(safeSession);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        dispatch.removeSession(session.getId());
        sessionRegistry.removeSession(session.getId());
        logger.info("Client WebSocket connection closed: {} with status {}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        WebSocketSession safeSession = sessionRegistry.resolveSafeSession(session);
        String payload = message.getPayload();
        logger.trace("Received client message: {}", payload);

        try {
            JsonRpcInboundRequest request =
                    objectMapper.readValue(payload, new TypeReference<JsonRpcInboundRequest>() {});
            handleRequest(safeSession, request);
        } catch (Exception e) {
            logger.error("Error handling client message {}", payload, e);
            streamClientError(safeSession, null, JsonRpcError.InternalError(e));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void handleRequest(WebSocketSession session, JsonRpcInboundRequest request) {
        ClientRpcHandler handler = handlers.get(request.method());
        if (handler == null) {
            streamClientError(session, request.id(), JsonRpcError.MethodNotFound(request.method()));
            return;
        }

        Class<?> payloadType = handler.getPayloadType();
        Object paramsObj;
        try {
            paramsObj = JsonRpcInbound.deserializeParams(objectMapper, request.params(), payloadType);
        } catch (Exception e) {
            logger.warn("Invalid params for client method {}: {}", request.method(), e.getMessage());
            if (request.id() != null) {
                streamClientError(session, request.id(), JsonRpcError.InvalidParams(e.getMessage()));
            }
            return;
        }

        Flux result = JsonRpcInbound.invoke(
                () -> handler.handle(session.getId(), request.id(), (ClientRpcPayload) paramsObj));
        dispatch.streamClientResponses(session, request.id(), result);
    }

    private void streamClientError(WebSocketSession session, Object requestId, JsonRpcError error) {
        dispatch.streamClientResponses(session, requestId, Flux.error(new RpcErrorException(error)));
    }

    public int getAuthenticatedSessionCount() {
        return sessionRegistry.getAuthenticatedSessionCount();
    }

    public int getPendingSessionCount() {
        return sessionRegistry.getPendingSessionCount();
    }
}
