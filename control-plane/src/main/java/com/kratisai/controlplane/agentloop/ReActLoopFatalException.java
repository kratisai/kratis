package com.kratisai.controlplane.agentloop;

import java.util.Objects;

/**
 * Signals that the ReAct loop aborted immediately because the LLM provider returned a
 * non-recoverable error; repeating the same request cannot succeed.
 */
public class ReActLoopFatalException extends RuntimeException {

    private final LlmErrorCategory category;

    public ReActLoopFatalException(LlmErrorCategory category, Throwable cause) {
        super(category.userMessage(), cause);
        this.category = Objects.requireNonNull(category, "category");
    }

    public LlmErrorCategory category() {
        return category;
    }
}
