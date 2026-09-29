package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import reactor.core.publisher.Flux;

/**
 * Environment RPC handler. Must not touch {@code WebSocketSession} or {@code WebSocketDispatch}.
 * Return a {@code Flux} of result payloads — the transport subscribes after {@code handle} returns
 * (so after any {@code @Transactional} commit) and sends each item as a JSON-RPC response with the
 * request id. Sync validation errors are signaled by throwing {@link RpcErrorException}; async
 * stream failures by {@code Flux.error}. {@code Flux.empty()} means no response (inbound
 * notifications, or a reply that will be sent later). The transport deserializes and validates the
 * payload before the handler is invoked, so {@code params} is always a valid instance.
 * @param <P> inbound request payload type
 * @param <R> outbound result payload type
 */
public interface EnvironmentRpcHandler<P extends EnvironmentRpcPayload, R extends EnvironmentResponsePayload> {

    String getMethodName();

    Class<P> getPayloadType();

    Flux<R> handle(String sessionId, Object requestId, P params);
}
