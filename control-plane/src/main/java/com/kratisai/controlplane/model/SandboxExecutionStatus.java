package com.kratisai.controlplane.model;

/**
 * Lifecycle state of a {@link SandboxExecution}.
 *
 * <ul>
 *   <li>{@link #RUNNING} – The execution has been created and the command is being executed (or
 *       waiting for the sidecar to pick it up).
 *   <li>{@link #IDLE} – The agent finished a turn and waits for the next prompt; the sandbox is
 *       still alive.
 *   <li>{@link #COMPLETED} – The command finished with exit code 0.
 *   <li>{@link #FAILED} – The command finished with a non-zero exit code, or an error prevented
 *       dispatch/execution.
 *   <li>{@link #UNKNOWN} – The execution was abandoned before its outcome could be observed (its
 *       environment was lost without settling it). Terminal: treat it as neither success nor
 *       failure, like a process whose exit code was never delivered.
 * </ul>
 */
public enum SandboxExecutionStatus {
    RUNNING,
    IDLE,
    COMPLETED,
    FAILED,
    UNKNOWN
}
