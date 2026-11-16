package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AttributeKind {
    ENDPOINT,
    IDENTIFIER,
    METHOD,
    TECHNOLOGY,
    FILE;

    @JsonCreator
    public static AttributeKind from(String value) {
        if (value == null) {
            return null;
        }
        for (AttributeKind candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
