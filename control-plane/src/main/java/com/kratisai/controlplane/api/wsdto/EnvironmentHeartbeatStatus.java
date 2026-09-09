package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EnvironmentHeartbeatStatus {
    OK("ok");

    private final String value;

    EnvironmentHeartbeatStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
