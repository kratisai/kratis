package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EnvironmentRegisterStatus {
    REGISTERED("registered"),
    RECONNECTED("reconnected");

    private final String value;

    EnvironmentRegisterStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
