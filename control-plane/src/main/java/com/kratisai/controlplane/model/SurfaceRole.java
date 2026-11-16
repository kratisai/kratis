package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum SurfaceRole {
    PROVIDES,
    REQUIRES,
    BIDIRECTIONAL,
    UNKNOWN;

    @JsonCreator
    public static SurfaceRole from(String value) {
        if (value == null) {
            return null;
        }
        for (SurfaceRole candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
