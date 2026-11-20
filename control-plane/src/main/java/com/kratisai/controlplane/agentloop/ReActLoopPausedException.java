package com.kratisai.controlplane.agentloop;

/** Signals that a chat turn paused because the server is shutting down; it can resume after restart. */
public class ReActLoopPausedException extends RuntimeException {

    public ReActLoopPausedException(String message, Throwable cause) {
        super(message, cause);
    }
}
