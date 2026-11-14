package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** The role a repository plays, stated by the synopsis. */
public enum RepoRole {
    SERVICE,
    WEB_APP,
    MOBILE_APP,
    LIBRARY,
    INFRA,
    TOOLING,
    DOCS,
    DATA;

    @JsonCreator
    public static RepoRole from(String value) {
        if (value == null) {
            return null;
        }
        for (RepoRole candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
