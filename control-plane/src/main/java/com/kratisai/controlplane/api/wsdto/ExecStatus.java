package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Result status for env.exec. */
public enum ExecStatus {
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    ExecStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
