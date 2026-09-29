package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static MessageRole fromString(String val) {
        for (MessageRole role : values()) {
            if (role.value.equalsIgnoreCase(val) || role.name().equalsIgnoreCase(val)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown message role: " + val);
    }
}
