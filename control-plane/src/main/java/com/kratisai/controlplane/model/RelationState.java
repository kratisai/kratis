package com.kratisai.controlplane.model;

/**
 * Lifecycle of a relation row. A new ingestion supersedes the rows that cited the batch it replaces;
 * {@code SUPERSEDED} rows are kept for history.
 */
public enum RelationState {
    CURRENT,
    SUPERSEDED
}
