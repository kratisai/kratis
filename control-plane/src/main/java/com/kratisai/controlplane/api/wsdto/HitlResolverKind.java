package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a HITL request was resolved: by a user, a remembered rule, a timeout, or the system. */
public enum HitlResolverKind {
    USER("user"),
    RULE("rule"),
    TIMEOUT("timeout"),
    SYSTEM("system");

    private final String value;

    HitlResolverKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static HitlResolverKind fromString(String val) {
        for (HitlResolverKind kind : values()) {
            if (kind.value.equalsIgnoreCase(val) || kind.name().equalsIgnoreCase(val)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown HITL resolver kind: " + val);
    }
}
