package com.kratisai.controlplane.agentloop;

import org.jspecify.annotations.Nullable;

/** Signals that the loop exhausted its iteration budget without producing a final result. */
public class ReActLoopExhaustedException extends RuntimeException {

    private final String lastValidationError;

    public ReActLoopExhaustedException(int maxIterations, @Nullable String lastValidationError) {
        super("Agent exceeded maximum iterations (" + maxIterations + ").");
        this.lastValidationError = lastValidationError;
    }

    public @Nullable String lastValidationError() {
        return lastValidationError;
    }
}
