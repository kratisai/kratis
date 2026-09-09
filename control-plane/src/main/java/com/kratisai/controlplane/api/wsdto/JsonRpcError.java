package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") Object data) {

    public static JsonRpcError error(int code, String message, Object data) {
        return new JsonRpcError(code, message, data);
    }

    public static JsonRpcError InternalError(Throwable e) {
        return error(JsonRpcErrorCodes.INTERNAL_ERROR, "Internal error", e.getMessage());
    }

    public static JsonRpcError MethodNotFound(String method) {
        return error(JsonRpcErrorCodes.METHOD_NOT_FOUND, "METHOD_NOT_FOUND", method);
    }

    public static JsonRpcError InvalidParams(String errorMessage) {
        return error(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid params", errorMessage);
    }

    public static JsonRpcError InvalidToken() {
        return error(JsonRpcErrorCodes.INVALID_TOKEN, "Invalid token", "Token is invalid or expired");
    }

    public static JsonRpcError NotAuthenticated() {
        return error(JsonRpcErrorCodes.NOT_AUTHENTICATED, "Not authenticated", "You must authenticate first");
    }

    public static JsonRpcError NotAuthorised(Object data) {
        return error(JsonRpcErrorCodes.NOT_AUTHORIZED, "Not authorised", data);
    }
}
