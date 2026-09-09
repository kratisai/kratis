package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcRequest;
import com.kratisai.controlplane.api.wsdto.JsonRpcResponse;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;

/**
 * Single transport path for every WebSocket write. The only class permitted to call {@code
 * session.sendMessage} (ArchUnit: {@code ONLY_DISPATCH_CALLS_WEBSOCKET_SEND}). A write failure
 * throws {@link WebSocketSendException}; there is no best-effort variant.
 *
 * <p>Broadcasts are one-to-many, so each recipient's write is serialized per client session over a
 * shared pool: a slow client can't block the caller or other recipients, and per-client order is
 * preserved. Session-directed streams stay synchronous (point-to-point).
 */
@Component
public class WebSocketDispatch {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketDispatch.class);

    private final ObjectMapper objectMapper;
    private final ClientSessionRegistry clientSessionRegistry;
    private final SubscriptionRegistry subscriptionRegistry;
    private final Executor broadcastExecutor;
    private final Map<String, SerialExecutor> clientBroadcastExecutors = new ConcurrentHashMap<>();

    public WebSocketDispatch(
            ObjectMapper objectMapper,
            ClientSessionRegistry clientSessionRegistry,
            SubscriptionRegistry subscriptionRegistry,
            @Qualifier("clientBroadcastExecutor") Executor broadcastExecutor) {
        this.objectMapper = objectMapper;
        this.clientSessionRegistry = clientSessionRegistry;
        this.subscriptionRegistry = subscriptionRegistry;
        this.broadcastExecutor = broadcastExecutor;
    }

    public <R extends ClientPayload> void streamClientResponses(WebSocketSession to, Object requestId, Flux<R> flux) {
        subscribeAndSend(to, requestId, flux, this::sendResponse);
    }

    public <R extends EnvironmentResponsePayload> void streamEnvironmentResponses(
            WebSocketSession to, Object requestId, Flux<R> flux) {
        subscribeAndSend(to, requestId, flux, this::sendResponse);
    }

    <P extends ClientPayload> void broadcastNotificationToTeam(UUID teamId, P payload) {
        JsonRpcResponse<P> notification = new JsonRpcResponse<>(payload, null);
        Set<String> sessionIds = subscriptionRegistry.getSubscribers(teamId);
        for (String sessionId : sessionIds) {
            WebSocketSession session = clientSessionRegistry.getSession(sessionId);
            if (session != null && session.isOpen()) {
                broadcastToSession(session, notification);
            }
        }
    }

    <P extends ClientPayload> void broadcastNotificationToUser(UUID userId, P payload) {
        JsonRpcResponse<P> notification = new JsonRpcResponse<>(payload, null);
        for (WebSocketSession session : clientSessionRegistry.getSessionsForUser(userId)) {
            if (session.isOpen()) {
                broadcastToSession(session, notification);
            }
        }
    }

    public void removeSession(String sessionId) {
        clientBroadcastExecutors.remove(sessionId);
    }

    int getBroadcastExecutorCount() {
        return clientBroadcastExecutors.size();
    }

    private void broadcastToSession(WebSocketSession session, JsonRpcResponse<?> notification) {
        clientBroadcastExecutors
                .computeIfAbsent(session.getId(), id -> new SerialExecutor(broadcastExecutor))
                .execute(() -> {
                    try {
                        writeAndSend(session, notification);
                    } catch (Exception e) {
                        logger.error(
                                "Failed to broadcast notification to session {}: {}", session.getId(), e.toString());
                        clientBroadcastExecutors.remove(session.getId());
                    }
                });
    }

    <P extends EnvironmentRpcPayload.OutboundRequestPayload<?>> void sendRequest(
            WebSocketSession to, P payload, Object id) {
        writeAndSend(to, new JsonRpcRequest<>(payload, id));
    }

    void sendRequest(WebSocketSession to, EnvironmentRpcPayload.OutboundNotificationPayload payload) {
        writeAndSend(to, new JsonRpcRequest<>(payload, null));
    }

    void sendResponse(WebSocketSession to, Object requestId, EnvironmentResponsePayload payload) {
        writeAndSend(to, new JsonRpcResponse<>(payload, requestId));
    }

    void sendError(WebSocketSession to, Object requestId, JsonRpcError error) {
        writeAndSend(to, new JsonRpcResponse<>(error, requestId));
    }

    private void sendResponse(WebSocketSession to, Object requestId, ClientPayload payload) {
        writeAndSend(to, new JsonRpcResponse<>(payload, requestId));
    }

    private <R> void subscribeAndSend(WebSocketSession to, Object requestId, Flux<R> flux, ResponseSender<R> sender) {
        flux.subscribe(payload -> sender.send(to, requestId, payload), error -> {
            if (error instanceof RpcErrorException rpcError) {
                sendError(to, requestId, rpcError.error());
                return;
            }
            logger.error("WebSocket stream failed for session {}", to.getId(), error);
            sendError(to, requestId, JsonRpcError.InternalError(error));
        });
    }

    private void writeAndSend(WebSocketSession session, Object envelope) {
        Objects.requireNonNull(session, "session is required");
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
        } catch (IOException e) {
            throw new WebSocketSendException("Failed to send WebSocket message to session " + session.getId(), e);
        }
    }

    @FunctionalInterface
    private interface ResponseSender<R> {
        void send(WebSocketSession to, Object requestId, R payload);
    }

    /**
     * Preserves submission order per client session — execution output chunks must arrive in order.
     */
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
