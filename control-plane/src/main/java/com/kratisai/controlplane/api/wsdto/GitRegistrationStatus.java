package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Result status shared by the git identity and credential registration methods. */
public enum GitRegistrationStatus {
    SUCCESS("success");

    private final String value;

    GitRegistrationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
