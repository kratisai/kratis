package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SandboxExecutionActiveDurationTest {

    @Test
    void activeDurationAccumulatesOnlyWhileRunning() {
        SandboxExecution execution = new SandboxExecution();
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setActiveSince(Instant.now().minusSeconds(60));
        execution.setStatus(SandboxExecutionStatus.IDLE);

        // The 60s RUNNING period counts; the transition to IDLE closes it.
        assertThat(execution.getTotalActiveSeconds()).isEqualTo(60);
        assertThat(execution.getActiveSince()).isNull();

        // An idle period must not accrue; re-entering RUNNING opens a new segment.
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setActiveSince(Instant.now().minusSeconds(30));
        execution.setStatus(SandboxExecutionStatus.FAILED);

        assertThat(execution.getTotalActiveSeconds()).isEqualTo(90);
    }

    @Test
    void activeDurationIncludesInFlightRunningPeriod() {
        SandboxExecution execution = new SandboxExecution();
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setActiveSince(Instant.now().minusSeconds(45));

        assertThat(execution.getTotalActiveSeconds()).isGreaterThanOrEqualTo(44);
    }

    @Test
    void repeatedRunningTransitionDoesNotRestartSegment() {
        SandboxExecution execution = new SandboxExecution();
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setActiveSince(Instant.now().minusSeconds(10));
        execution.setStatus(SandboxExecutionStatus.RUNNING);

        assertThat(execution.getActiveSince()).isNotNull();
        assertThat(execution.getTotalActiveSeconds()).isGreaterThanOrEqualTo(10);
    }
}
