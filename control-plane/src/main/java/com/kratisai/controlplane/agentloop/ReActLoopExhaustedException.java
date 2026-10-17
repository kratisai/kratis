package com.kratisai.controlplane.agentloop;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;

/** Signals that the loop exhausted its iteration budget without producing a final result. */
public class ReActLoopExhaustedException extends RuntimeException {

    private final String lastValidationError;
    private final List<Message> history;

    public ReActLoopExhaustedException(int maxIterations, @Nullable String lastValidationError, List<Message> history) {
        super("Agent exceeded maximum iterations (" + maxIterations + ").");
        this.lastValidationError = lastValidationError;
        this.history = history != null ? List.copyOf(history) : List.of();
    }

    public @Nullable String lastValidationError() {
        return lastValidationError;
    }

    public List<Message> history() {
        return history;
    }
}
