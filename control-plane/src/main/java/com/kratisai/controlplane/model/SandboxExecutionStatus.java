package com.kratisai.controlplane.model;

/**
 * Represents the lifecycle state of a {@link SandboxExecution}.
 *
 * <ul>
 *   <li>{@link #RUNNING} – The execution has been created and the command is being executed (or
 *       waiting for the sidecar to pick it up).
 *   <li>{@link #COMPLETED} – The command finished with exit code 0.
 *   <li>{@link #FAILED} – The command finished with a non-zero exit code, or an error prevented
 *       dispatch/execution.
 * </ul>
 */
public enum SandboxExecutionStatus {
    RUNNING,
    IDLE,
    COMPLETED,
    FAILED
}
