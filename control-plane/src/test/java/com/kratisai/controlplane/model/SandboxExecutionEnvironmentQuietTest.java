package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxExecutionEnvironmentQuietTest {

    private static final Instant LONG_AGO = Instant.now().minus(Duration.ofDays(1));

    private static SandboxExecution runningExecutionOn(ExecutionEnvironment environment) {
        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        return execution;
    }

    private static ExecutionEnvironment environment() {
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setStateChangedAt(LONG_AGO);
        return environment;
    }

    @Test
    void everyExitFromRunningRestartsTheEnvironmentClock() {
        for (SandboxExecutionStatus next : List.of(
                SandboxExecutionStatus.IDLE,
                SandboxExecutionStatus.COMPLETED,
                SandboxExecutionStatus.FAILED,
                SandboxExecutionStatus.UNKNOWN)) {
            ExecutionEnvironment environment = environment();
            SandboxExecution execution = runningExecutionOn(environment);

            execution.setStatus(next);

            assertThat(environment.getStateChangedAt()).as(next.name()).isAfter(LONG_AGO);
        }
    }

    @Test
    void transitionsNotLeavingRunningDoNotTouchTheEnvironmentClock() {
        ExecutionEnvironment environment = environment();
        SandboxExecution execution = runningExecutionOn(environment);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        assertThat(environment.getStateChangedAt()).isEqualTo(LONG_AGO);

        execution.setStatus(SandboxExecutionStatus.IDLE);
        Instant afterQuiet = environment.getStateChangedAt();
        execution.setStatus(SandboxExecutionStatus.COMPLETED);

        assertThat(environment.getStateChangedAt()).isEqualTo(afterQuiet);
    }

    @Test
    void executionWithoutEnvironmentCanStillLeaveRunning() {
        SandboxExecution execution = runningExecutionOn(null);

        execution.setStatus(SandboxExecutionStatus.IDLE);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
    }
}
