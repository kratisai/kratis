package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RegisterGitAuthStatus {
    SUCCESS("success");

    private final String value;

    RegisterGitAuthStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
