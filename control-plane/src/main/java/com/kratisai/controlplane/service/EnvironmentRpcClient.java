package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Typed outbound transport for control-plane → connector JSON-RPC requests, with request
 * correlation. All wire writes delegate to {@link WebSocketDispatch}.
 */
@Component
public class EnvironmentRpcClient {

    private static final long DEFAULT_TIMEOUT_SECONDS = 600;

    private final ObjectMapper objectMapper;
    private final WebSocketDispatch dispatch;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();

    public EnvironmentRpcClient(
            ObjectMapper objectMapper, WebSocketDispatch dispatch, EnvironmentSessionRegistry sessionRegistry) {
        this.objectMapper = objectMapper;
        this.dispatch = dispatch;
        this.sessionRegistry = sessionRegistry;
    }

    public void send(UUID environmentId, EnvironmentRpcPayload.OutboundNotificationPayload payload) {
        Objects.requireNonNull(payload, "payload is required");
        dispatch.sendRequest(requireOpenSession(environmentId), payload);
    }

    public <R extends EnvironmentConnectorResult> R request(
            UUID environmentId, EnvironmentRpcPayload.OutboundRequestPayload<R> payload)
            throws InterruptedException, TimeoutException {
        return request(environmentId, payload, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public <R extends EnvironmentConnectorResult> R request(
            UUID environmentId, EnvironmentRpcPayload.OutboundRequestPayload<R> payload, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        Objects.requireNonNull(payload, "payload is required");
        WebSocketSession session = requireOpenSession(environmentId);

        String id = Long.toString(nextId.getAndIncrement());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, new PendingCall(session.getId(), future));

        try {
            dispatch.sendRequest(session, payload, id);
            JsonNode resultNode = future.get(timeout, unit);
            return resultNode == null || resultNode.isNull()
                    ? null
                    : objectMapper.convertValue(resultNode, payload.resultType());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof EnvironmentRpcException rpcEx) {
                throw rpcEx;
            }
            throw new EnvironmentRpcException(-32603, cause.getMessage() != null ? cause.getMessage() : "RPC failed");
        } finally {
            pending.remove(id);
        }
    }

    public void reply(String sessionId, Object requestId, EnvironmentResponsePayload payload) {
        WebSocketSession session = requireOpenSessionById(sessionId);
        dispatch.sendResponse(session, requestId, payload);
    }

    public void replyError(UUID environmentId, Object requestId, JsonRpcError error) {
        dispatch.sendError(requireOpenSession(environmentId), requestId, error);
    }

    /** Completes a pending waiter for an inbound JSON-RPC response ({@code id} present, no {@code method}). */
    public boolean completeResponse(JsonNode envelope) {
        if (envelope == null || !envelope.hasNonNull("id") || envelope.has("method")) {
            return false;
        }
        String id = envelope.get("id").asText();
        PendingCall call = pending.remove(id);
        if (call == null) {
            return false;
        }
        if (envelope.has("error") && !envelope.get("error").isNull()) {
            JsonNode error = envelope.get("error");
            int code = error.has("code") ? error.get("code").asInt(-32603) : -32603;
            String message = error.has("message") ? error.get("message").asText("RPC error") : "RPC error";
            JsonNode dataNode = error.get("data");
            String data = (dataNode != null && !dataNode.isNull()) ? dataNode.asText("") : null;
            call.future().completeExceptionally(new EnvironmentRpcException(code, message, data));
        } else {
            call.future().complete(envelope.get("result"));
        }
        return true;
    }

    public void failAll(String sessionId, String reason) {
        EnvironmentRpcException ex = new EnvironmentRpcException(-32000, reason);
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().sessionId().equals(sessionId)) {
                entry.getValue().future().completeExceptionally(ex);
                return true;
            }
            return false;
        });
    }

    private WebSocketSession requireOpenSession(UUID environmentId) {
        Objects.requireNonNull(environmentId, "environmentId is required");
        WebSocketSession session = sessionRegistry.getSessionForEnvironment(environmentId);
        if (session == null || !session.isOpen()) {
            throw new EnvironmentRpcException(-32000, "Environment WebSocket session is not connected");
        }
        return session;
    }

    private WebSocketSession requireOpenSessionById(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            throw new EnvironmentRpcException(-32000, "Environment WebSocket session is not connected");
        }
        return session;
    }

    private record PendingCall(String sessionId, CompletableFuture<JsonNode> future) {}

    public static final class EnvironmentRpcException extends RuntimeException {
        private final int code;
        private final String data;

        public EnvironmentRpcException(int code, String message) {
            this(code, message, null);
        }

        public EnvironmentRpcException(int code, String message, String data) {
            super(message);
            this.code = code;
            this.data = data;
        }

        public int getCode() {
            return code;
        }

        public String getData() {
            return data;
        }
    }
}
