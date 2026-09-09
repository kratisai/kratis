package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LaunchStatus {
    LAUNCHED("launched"),
    FAILED("failed");

    private final String value;

    LaunchStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
