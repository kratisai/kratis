package com.kratisai.controlplane.agentloop;

/**
 * Signals a recoverable tool-call failure. The ReAct loop catches it, logs it, returns it to the
 * model as an error tool result, and continues, so the agent can correct the call and retry.
 */
public class KratisToolException extends RuntimeException {

    public KratisToolException(String message) {
        super(message);
    }
}
