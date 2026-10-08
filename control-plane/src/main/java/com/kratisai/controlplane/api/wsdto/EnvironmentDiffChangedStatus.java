package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EnvironmentDiffChangedStatus {
    PERSISTED("persisted");

    private final String value;

    EnvironmentDiffChangedStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
