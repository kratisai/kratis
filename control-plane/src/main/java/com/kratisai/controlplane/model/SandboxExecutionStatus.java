package com.kratisai.controlplane.model;

/**
 * Lifecycle state of a {@link SandboxExecution}.
 *
 * <ul>
 *   <li>{@link #RUNNING} – A prompt is being processed, or the execution is waiting for the sidecar
 *       to start it.
 *   <li>{@link #IDLE} – No turn is in progress. The execution stays available for another prompt,
 *       including after a turn error, refusal, process exit, or lost runtime.
 *   <li>{@link #COMPLETED} – The user explicitly ended the execution.
 * </ul>
 */
public enum SandboxExecutionStatus {
    RUNNING,
    IDLE,
    COMPLETED
}
