package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CancelAgentStatus {
    COMPLETED("completed"),
    CANCELLED("cancelled");

    private final String value;

    CancelAgentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
