package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Closed set of ACP PlanEntryPriority values. */
public enum PlanEntryPriority {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String value;

    PlanEntryPriority(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PlanEntryPriority fromString(String val) {
        for (PlanEntryPriority priority : values()) {
            if (priority.value.equalsIgnoreCase(val) || priority.name().equalsIgnoreCase(val)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown plan entry priority: " + val);
    }
}
