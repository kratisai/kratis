package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NodeType {
    FILE,
    CLASS,
    INTERFACE,
    METHOD,
    FUNCTION,
    FIELD,
    VARIABLE,
    MODULE,
    PROJECT,
    RESOURCE,
    ARTIFACT,
    ROUTE,
    CHANNEL;

    @JsonValue
    public String toLowerCase() {
        return this.name().toLowerCase();
    }
}
