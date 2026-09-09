package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 request carrying a closed {@link RpcPayload}. The wire {@code method} derives from
 * the payload, so method/params can never disagree. Payloads marked {@link RpcPayload.NoParamsPayload}
 * serialize with the {@code params} member omitted. The transport layer reads raw inbound frames as
 * {@link JsonRpcInboundRequest}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest<P extends RpcPayload>(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("method") String method,
        @JsonProperty("params") P params,
        @JsonProperty("id") Object id) {

    public JsonRpcRequest(String jsonrpc, String method, P params, Object id) {
        if (jsonrpc == null) {
            jsonrpc = "2.0";
        }
        if (params != null && !params.method().equals(method)) {
            throw new IllegalArgumentException(
                    "Wire method '" + method + "' does not match payload method '" + params.method() + "'");
        }
        this.jsonrpc = jsonrpc;
        this.method = method;
        this.params = params;
        this.id = id;
    }

    /** Derives the wire method from the payload; {@code NoParamsPayload}s omit the params member. */
    public JsonRpcRequest(P payload, Object id) {
        this("2.0", payload.method(), payload instanceof RpcPayload.NoParamsPayload ? null : payload, id);
    }
}
