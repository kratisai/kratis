package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RelationType {
    DEFINES,
    CALLS,
    IMPORTS,
    INHERITS,
    IMPLEMENTS,
    HANDLES,
    THROWS,
    CONFIGURES,
    WRITES,
    TESTS,
    FILE_CHANGES_WITH,
    DECORATES,
    CROSS_REPO_CALL,
    SEMANTICALLY_SIMILAR;

    @JsonValue
    public String toLowerCase() {
        return this.name().toLowerCase();
    }
}
