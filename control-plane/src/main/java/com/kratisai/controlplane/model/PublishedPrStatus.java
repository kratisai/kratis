package com.kratisai.controlplane.model;

/** Lifecycle of a published pull request as cached from the git remote. */
public enum PublishedPrStatus {
    OPEN,
    MERGED,
    CLOSED
}
