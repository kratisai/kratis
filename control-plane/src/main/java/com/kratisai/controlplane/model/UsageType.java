package com.kratisai.controlplane.model;

/** Provenance of a usage log entry: which producer generated the LLM usage. */
public enum UsageType {
    /** LLM usage from sandbox executions. */
    EXECUTION,
    /** LLM usage from repository ingestion batches. */
    INGESTION,
    /** LLM usage from team wiki build batches. */
    TEAM_WIKI,
    /** LLM usage from chat usage sessions. */
    CHAT
}
