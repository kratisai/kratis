package com.kratisai.controlplane.model;

import java.util.Objects;

public enum TeamWikiTriggerKind {
    AUTOMATIC,
    MANUAL;

    public static TeamWikiTriggerKind requireNonNull(TeamWikiTriggerKind kind) {
        return Objects.requireNonNull(kind, "triggerKind");
    }
}
