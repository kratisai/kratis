package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The kind of a HITL request: authorize a command (approval) or answer a structured question (question). */
public enum HitlKind {
    APPROVAL("approval"),
    QUESTION("question");

    private final String value;

    HitlKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static HitlKind fromString(String val) {
        for (HitlKind kind : values()) {
            if (kind.value.equalsIgnoreCase(val) || kind.name().equalsIgnoreCase(val)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown HITL kind: " + val);
    }
}
