package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Wire values for the execution activity lifecycle (ACP ToolCallStatus). */
public enum ActivityStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    ActivityStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ActivityStatus fromString(String val) {
        for (ActivityStatus status : values()) {
            if (status.value.equalsIgnoreCase(val) || status.name().equalsIgnoreCase(val)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown activity status: " + val);
    }
}
