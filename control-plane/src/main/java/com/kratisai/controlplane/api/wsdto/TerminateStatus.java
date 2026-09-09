package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TerminateStatus {
    COMPLETED("completed"),
    TERMINATED("terminated");

    private final String value;

    TerminateStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
