package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum Ceiling {
    CONFIRMED,
    SUGGESTED;

    @JsonCreator
    public static Ceiling from(String value) {
        if (value == null) {
            return null;
        }
        for (Ceiling candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return null;
    }
}
