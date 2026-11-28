package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Topic status shown in the sidebar, derived from the latest execution. */
public enum TopicStatus {
    WORKING("working"),
    PLANNING("Planning"),
    IDLE("Idle"),
    MR_PUSHED("MR pushed"),
    MR_MERGED("MR merged"),
    READY_TO_PUBLISH("Ready to publish"),
    BLOCKED("Blocked");

    private final String value;

    TopicStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
