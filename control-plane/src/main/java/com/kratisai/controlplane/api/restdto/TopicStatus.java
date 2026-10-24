package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Coarse topic status shown in the sidebar, derived from the latest execution. */
public enum TopicStatus {
    WORKING("working"),
    IDLE("Idle"),
    MR_PUSHED("MR pushed"),
    BLOCKED("Blocked"),
    FAILED("Failed");

    private final String value;

    TopicStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
