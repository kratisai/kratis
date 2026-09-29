package com.kratisai.controlplane.api.wsdto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Thrown by RPC handlers to signal a JSON-RPC error response. The transport catches it and sends
 * the error correlated with the originating request id.
 */
public final class RpcErrorException extends RuntimeException {

    // JsonRpcError holds a JsonNode payload that is not java-serializable; the exception is
    // only thrown and caught in-process, never serialized.
    @SuppressFBWarnings("SE_BAD_FIELD")
    private final JsonRpcError error;

    public RpcErrorException(JsonRpcError error) {
        super(error.message());
        this.error = error;
    }

    public JsonRpcError error() {
        return error;
    }
}
