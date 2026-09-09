package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** ACP session/prompt stop reasons (env.acp_prompt_complete). */
public enum StopReason {
    END_TURN("end_turn"),
    MAX_TOKENS("max_tokens"),
    MAX_TURN_REQUESTS("max_turn_requests"),
    REFUSAL("refusal"),
    CANCELLED("cancelled");

    private final String value;

    StopReason(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static StopReason fromString(String val) {
        for (StopReason reason : values()) {
            if (reason.value.equals(val) || reason.name().equalsIgnoreCase(val)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown stop reason: " + val);
    }
}
