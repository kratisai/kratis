package com.kratisai.controlplane.websocket.client;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import reactor.core.publisher.Flux;

/**
 * The transport calling "handle" subscribes to the flux after {@code handle} returns (so
 * after any {@code @Transactional} commit) and sends each item as a JSON-RPC response with the
 * request id. Sync validation errors are signaled by throwing {@link RpcErrorException}; async
 * stream failures by {@code Flux.error}. {@code Flux.empty()} means no response. The transport
 * deserializes and validates the payload before the handler is invoked, so {@code params} is
 * always a valid instance.
 * @param <P> inbound request payload type
 * @param <R> outbound result payload type
 */
public interface ClientRpcHandler<P extends ClientRpcPayload, R extends ClientPayload> {

    String getMethodName();

    Class<P> getPayloadType();

    Flux<R> handle(String sessionId, Object requestId, P params);
}
