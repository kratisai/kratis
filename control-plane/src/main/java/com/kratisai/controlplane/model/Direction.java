package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Which side of a pair is upstream. Rows are stored with the pair ordered by SQL uuid semantics. */
public enum Direction {
    A_TO_B,
    B_TO_A,
    BIDIRECTIONAL,
    UNKNOWN;

    @JsonCreator
    public static Direction from(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (Direction candidate : values()) {
            if (candidate.name().equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        return null;
    }
}
