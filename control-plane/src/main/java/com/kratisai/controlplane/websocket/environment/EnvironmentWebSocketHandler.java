package com.kratisai.controlplane.websocket.environment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.config.WebSocketConfig;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import com.kratisai.controlplane.service.WebSocketDispatch;
import com.kratisai.controlplane.websocket.JsonRpcInbound;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;

/**
 * Environment ({@code /ws/env}) JSON-RPC handler. Sessions are wrapped in {@link
 * ConcurrentWebSocketSessionDecorator} at connect so concurrent writers share one ordered send
 * path.
 *
 * <p>Inbound messages use a dispatcher: the reader thread only parses the envelope and enqueues
 * request processing to a per-session serial executor over the shared {@code envMessageExecutor}
 * pool, so handler work can't back-pressure the sidecar. Responses are completed inline on the
 * reader so the waiting virtual thread resumes promptly.
 */
@Component
public class EnvironmentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentWebSocketHandler.class);

    private static final long CLEANUP_INTERVAL_SECONDS = 10;

    private final ObjectMapper objectMapper;

    @SuppressWarnings("rawtypes")
    private final Map<String, EnvironmentRpcHandler> handlers = new ConcurrentHashMap<>();

    private final EnvironmentSessionRegistry sessionRegistry;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final EnvironmentRpcClient environmentRpcClient;
    private final WebSocketDispatch dispatch;
    private final Executor messageExecutor;
    private final Map<String, SerialExecutor> sessionExecutors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "env-ws-cleanup");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("rawtypes")
    public EnvironmentWebSocketHandler(
            List<EnvironmentRpcHandler> rpcHandlers,
            EnvironmentSessionRegistry sessionRegistry,
            PendingHitlRegistry pendingHitlRegistry,
            EnvironmentRpcClient environmentRpcClient,
            WebSocketDispatch dispatch,
            ObjectMapper objectMapper,
            @Qualifier("envMessageExecutor") Executor messageExecutor) {
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.sessionRegistry = sessionRegistry;
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.environmentRpcClient = environmentRpcClient;
        this.dispatch = dispatch;
        this.messageExecutor = messageExecutor;
        for (EnvironmentRpcHandler handler : rpcHandlers) {
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
        logger.info("Environment WebSocket connection established: {}", session.getId());
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, WebSocketConfig.CONCURRENT_SEND_TIME_LIMIT_MS, WebSocketConfig.CONCURRENT_SEND_BUFFER_SIZE);
        sessionRegistry.registerPendingSession(safeSession);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        sessionExecutors.remove(session.getId());
        var removedHitl = pendingHitlRegistry.removeBySession(session);
        if (!removedHitl.isEmpty()) {
            logger.info(
                    "Cleaned up {} pending HITL requests for disconnected session {}",
                    removedHitl.size(),
                    session.getId());
        }
        environmentRpcClient.failAll(session.getId(), "Environment WebSocket session closed: " + session.getId());
        sessionRegistry.removeSession(session.getId());
        logger.info("Environment WebSocket connection closed: {} with status {}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        WebSocketSession safeSession = sessionRegistry.resolveSafeSession(session);
        String payload = message.getPayload();
        logger.trace("Received environment message: {}", payload);

        try {
            JsonNode envelope = objectMapper.readTree(payload);
            if (envelope.hasNonNull("id") && !envelope.has("method")) {
                // Completed inline so the virtual thread waiting in EnvironmentRpcClient resumes promptly.
                if (environmentRpcClient.completeResponse(envelope)) {
                    return;
                }
                logger.debug(
                        "Unmatched JSON-RPC response id={} from environment session {}",
                        envelope.get("id"),
                        session.getId());
                return;
            }
            serialExecutorFor(session.getId()).execute(() -> processRequest(safeSession, envelope));
        } catch (Exception e) {
            logger.error("Error handling environment message {}", payload, e);
            streamEnvironmentError(safeSession, null, JsonRpcError.InternalError(e));
        }
    }

    private SerialExecutor serialExecutorFor(String sessionId) {
        return sessionExecutors.computeIfAbsent(sessionId, id -> new SerialExecutor(messageExecutor));
    }

    private void processRequest(WebSocketSession session, JsonNode envelope) {
        try {
            JsonRpcInboundRequest request =
                    objectMapper.treeToValue(envelope, new TypeReference<JsonRpcInboundRequest>() {});
            handleRequest(session, request);
        } catch (Exception e) {
            logger.error("Error handling environment message {}", envelope, e);
            streamEnvironmentError(session, null, JsonRpcError.InternalError(e));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void handleRequest(WebSocketSession session, JsonRpcInboundRequest request) {
        EnvironmentRpcHandler handler = handlers.get(request.method());
        if (handler == null) {
            streamEnvironmentError(session, request.id(), JsonRpcError.MethodNotFound(request.method()));
            return;
        }

        Class<?> payloadType = handler.getPayloadType();
        Object paramsObj;
        try {
            paramsObj = JsonRpcInbound.deserializeParams(objectMapper, request.params(), payloadType);
        } catch (Exception e) {
            logger.warn("Invalid params for environment method {}: {}", request.method(), e.getMessage());
            if (request.id() != null) {
                streamEnvironmentError(session, request.id(), JsonRpcError.InvalidParams(e.getMessage()));
            }
            return;
        }

        logger.trace("Handling {}: {}", request.method(), paramsObj);
        Flux result = JsonRpcInbound.invoke(
                () -> handler.handle(session.getId(), request.id(), (EnvironmentRpcPayload) paramsObj));
        dispatch.streamEnvironmentResponses(session, request.id(), result);
    }

    private void streamEnvironmentError(WebSocketSession session, Object requestId, JsonRpcError error) {
        dispatch.streamEnvironmentResponses(session, requestId, Flux.error(new RpcErrorException(error)));
    }

    public int getEnvironmentSessionCount() {
        return sessionRegistry.getEnvironmentSessionCount();
    }

    public int getPendingSessionCount() {
        return sessionRegistry.getPendingSessionCount();
    }

    private static final class SerialExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Executor delegate;
        private boolean running;

        private SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void execute(@NonNull Runnable command) {
            tasks.add(() -> {
                try {
                    command.run();
                } finally {
                    scheduleNext();
                }
            });
            if (!running) {
                running = true;
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            Runnable task = tasks.poll();
            if (task != null) {
                delegate.execute(task);
            } else {
                running = false;
            }
        }
    }
}
