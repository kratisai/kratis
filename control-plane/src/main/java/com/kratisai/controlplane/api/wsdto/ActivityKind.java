package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Categories of tools that can be invoked.
 * Tool kinds help clients choose appropriate icons and optimize how they
 * display tool execution progress.
 */
public enum ActivityKind {
    READ("read"),
    EDIT("edit"),
    DELETE("delete"),
    MOVE("move"),
    SEARCH("search"),
    EXECUTE("execute"),
    THINK("think"),
    FETCH("fetch"),
    SWITCH_MODE("switch_mode"),
    OTHER("other");

    private final String value;

    ActivityKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ActivityKind fromString(String val) {
        for (ActivityKind kind : values()) {
            if (kind.value.equalsIgnoreCase(val) || kind.name().equalsIgnoreCase(val)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown activity kind: " + val);
    }
}
