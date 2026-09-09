package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DimensionCategory {
    DOMAIN,
    ARCHETYPE,
    CROSS_CUTTING;

    @JsonValue
    public String toLowerCase() {
        return this.name().toLowerCase();
    }
}
