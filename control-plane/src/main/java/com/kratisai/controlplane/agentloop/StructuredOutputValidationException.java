package com.kratisai.controlplane.agentloop;

/**
 * Signals that the final response failed structured validation. The message is corrective guidance
 * for the model, added to the conversation so the loop can retry.
 */
class StructuredOutputValidationException extends RuntimeException {

    StructuredOutputValidationException(String guidance) {
        super(guidance);
    }
}
