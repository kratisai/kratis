package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse<R>(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("result") R result,
        @JsonProperty("error") JsonRpcError error,
        @JsonProperty("id") Object id) {
    public JsonRpcResponse {
        if (jsonrpc == null) {
            jsonrpc = "2.0";
        }
    }

    /** Create a success response with a typed result. */
    public JsonRpcResponse(R result, Object id) {
        this("2.0", result, null, id);
    }

    /** Create an error response. */
    public JsonRpcResponse(JsonRpcError error, Object id) {
        this("2.0", null, error, id);
    }
}
