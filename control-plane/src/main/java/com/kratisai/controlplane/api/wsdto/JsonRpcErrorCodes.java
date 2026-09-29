package com.kratisai.controlplane.api.wsdto;

/**
 * JSON-RPC 2.0 Error Codes as defined in the specification.
 *
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 Specification -
 *     Error Object</a>
 */
public final class JsonRpcErrorCodes {

    private JsonRpcErrorCodes() {
        // Utility class - prevent instantiation
    }

    // Standard JSON-RPC 2.0 error codes
    /** Invalid JSON was received by the server. */
    public static final int PARSE_ERROR = -32700;

    /** The JSON sent is not a valid Request object. */
    public static final int INVALID_REQUEST = -32600;

    /** The method does not exist / is not available. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** Invalid method parameter(s). */
    public static final int INVALID_PARAMS = -32602;

    /** Internal JSON-RPC error. */
    public static final int INTERNAL_ERROR = -32603;

    // Server error range (reserved for implementation-defined server-errors)
    /** Start of server-defined error range. */
    public static final int SERVER_ERROR_START = -32000;

    /** End of server-defined error range. */
    public static final int SERVER_ERROR_END = -32099;

    // Application-specific error codes (Kratis-defined)
    /** Authentication required but not provided. */
    public static final int NOT_AUTHENTICATED = -32000;

    /** Authentication token is invalid or expired. */
    public static final int INVALID_TOKEN = -32001;

    /** User is not authorized to perform this action. */
    public static final int NOT_AUTHORIZED = -32002;

    /** Resource not found. */
    public static final int RESOURCE_NOT_FOUND = -32003;

    /** Resource conflict (e.g., duplicate name). */
    public static final int RESOURCE_CONFLICT = -32004;

    /** Rate limit exceeded. */
    public static final int RATE_LIMIT_EXCEEDED = -32005;

    // Agent-specific error codes
    /** LLM provider not found or not available. */
    public static final int LLM_PROVIDER_NOT_FOUND = -32006;

    /** LLM provider is inactive. */
    public static final int LLM_PROVIDER_INACTIVE = -32007;

    /** Maximum iterations exceeded in agent ReAct loop. */
    public static final int MAX_ITERATIONS_EXCEEDED = -32008;

    /** Execution was aborted by user. */
    public static final int EXECUTION_ABORTED = -32009;

    /** LLM provider rejected the credentials (invalid or revoked API key). */
    public static final int LLM_AUTHENTICATION_FAILED = -32010;

    /** Conversation exceeded the model's context window. */
    public static final int LLM_CONTEXT_LENGTH_EXCEEDED = -32011;
}
