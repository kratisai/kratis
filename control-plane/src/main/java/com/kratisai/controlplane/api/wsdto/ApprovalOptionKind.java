package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The nature of a permission option.
 * Helps clients choose appropriate icons and UI treatment.
 */
public enum ApprovalOptionKind {
    ALLOW_ONCE("allow_once"),
    ALLOW_ALWAYS("allow_always"),
    REJECT_ONCE("reject_once"),
    REJECT_ALWAYS("reject_always");

    private final String value;

    ApprovalOptionKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ApprovalOptionKind fromString(String val) {
        for (ApprovalOptionKind kind : values()) {
            if (kind.value.equalsIgnoreCase(val) || kind.name().equalsIgnoreCase(val)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown approval option kind: " + val);
    }
}
