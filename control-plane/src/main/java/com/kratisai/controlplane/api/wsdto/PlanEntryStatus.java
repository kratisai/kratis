package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Closed set of ACP PlanEntryStatus values. */
public enum PlanEntryStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String value;

    PlanEntryStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlanEntryStatus fromString(String val) {
        for (PlanEntryStatus status : values()) {
            if (status.value.equalsIgnoreCase(val) || status.name().equalsIgnoreCase(val)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown plan entry status: " + val);
    }
}
