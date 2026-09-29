package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Status of changed file in diff summary. */
public enum GitDiffStatus {
    MODIFIED("MODIFIED"),
    ADDED("ADDED"),
    DELETED("DELETED"),
    RENAMED("RENAMED");

    private final String value;

    GitDiffStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
