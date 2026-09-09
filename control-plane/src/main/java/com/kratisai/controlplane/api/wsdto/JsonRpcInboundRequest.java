package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Raw inbound JSON-RPC frame as read from the wire: {@code params} is still a {@link JsonNode}
 * until the transport deserializes it into the closed {@link RpcPayload} record of the target
 * method. Outbound requests use {@link JsonRpcRequest}, which enforces a typed, closed payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcInboundRequest(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("method") String method,
        @JsonProperty("params") JsonNode params,
        @JsonProperty("id") Object id) {
    public JsonRpcInboundRequest {
        if (jsonrpc == null) {
            jsonrpc = "2.0";
        }
    }

    public JsonRpcInboundRequest(String method, JsonNode params, Object id) {
        this("2.0", method, params, id);
    }
}
