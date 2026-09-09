package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PromptStatus {
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    PromptStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
