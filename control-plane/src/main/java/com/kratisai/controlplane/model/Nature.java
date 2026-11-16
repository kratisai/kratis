package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum Nature {
    CALLS,
    MESSAGES,
    BUILDS_AGAINST,
    HOSTS,
    SHARES_STATE,
    PEER;

    @JsonCreator
    public static Nature from(String value) {
        if (value == null) {
            return null;
        }
        for (Nature candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return null;
    }
}
