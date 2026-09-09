package com.kratisai.controlplane.agentloop;

/** Classification of an LLM provider failure, driving the ReAct loop retry policy. */
public enum LlmErrorCategory {
    /** Invalid or revoked credentials; retrying cannot succeed. */
    AUTHENTICATION(
            FailureKind.FATAL, "LLM provider authentication failed. Check the configured API key or credentials."),
    /** The authenticated key is not permitted to use the requested model. */
    PERMISSION_DENIED(FailureKind.FATAL, "LLM provider denied access to the requested model or capability."),
    /** Account balance, credit, or budget is exhausted. */
    QUOTA_EXCEEDED(
            FailureKind.FATAL, "LLM provider quota or budget is exhausted. Top up the account or raise the limit."),
    /** Prompt plus history exceeds the model context window; retrying grows the prompt. */
    CONTEXT_LENGTH_EXCEEDED(
            FailureKind.FATAL,
            "The conversation exceeds the model context window. Start a new chat or shorten the request."),
    /** Unknown model or deployment name. */
    MODEL_NOT_FOUND(FailureKind.FATAL, "The requested model was not found on the LLM provider."),
    /** The provider rejected an otherwise well-formed request. */
    BAD_REQUEST(FailureKind.FATAL, "The LLM provider rejected the request."),
    /** Provider-enforced throughput limit that clears on its own. */
    RATE_LIMIT(FailureKind.TRANSIENT, "The LLM provider rate limit was reached. Try again shortly."),
    /** 5xx provider failure. */
    SERVER_ERROR(FailureKind.TRANSIENT, "The LLM provider returned a server error. Try again shortly."),
    /** Transport-level failure such as a timeout, reset, or refused connection. */
    NETWORK_ERROR(
            FailureKind.TRANSIENT, "A network error occurred while contacting the LLM provider. Try again shortly."),
    /** Not a recognisable provider failure; may be correctable by feeding guidance to the model. */
    UNKNOWN(FailureKind.MODEL_CORRECTABLE, "The LLM request failed unexpectedly.");

    private enum FailureKind {
        FATAL,
        TRANSIENT,
        MODEL_CORRECTABLE
    }

    private final FailureKind kind;
    private final String userMessage;

    LlmErrorCategory(FailureKind kind, String userMessage) {
        this.kind = kind;
        this.userMessage = userMessage;
    }

    /** True when repeating the same request can never succeed, so the loop must abort. */
    public boolean isFatal() {
        return kind == FailureKind.FATAL;
    }

    /** True when the failure is transport-level and may clear with a bounded backoff retry. */
    public boolean isTransient() {
        return kind == FailureKind.TRANSIENT;
    }

    /** User-facing message safe to surface in chat, ingestion logs, and batch error state. */
    public String userMessage() {
        return userMessage;
    }
}
