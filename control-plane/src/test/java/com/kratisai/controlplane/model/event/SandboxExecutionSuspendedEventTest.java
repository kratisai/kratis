package com.kratisai.controlplane.model.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// The null-rejection tests deliberately pass null to a constructor whose
// parameters are non-null, which SpotBugs flags as NP_NULL_PARAM_DEREF_NONVIRTUAL.
@SuppressFBWarnings("NP_NULL_PARAM_DEREF_NONVIRTUAL")
class SandboxExecutionSuspendedEventTest {

    @Test
    void constructor_acceptsNonNullIds() {
        UUID teamId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();

        SandboxExecutionSuspendedEvent event = new SandboxExecutionSuspendedEvent(teamId, executionId, environmentId);

        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.environmentId()).isEqualTo(environmentId);
    }

    @Test
    void constructor_rejectsNullTeamId() {
        assertThatThrownBy(() -> new SandboxExecutionSuspendedEvent(null, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("teamId");
    }

    @Test
    void constructor_rejectsNullExecutionId() {
        assertThatThrownBy(() -> new SandboxExecutionSuspendedEvent(UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("executionId");
    }

    @Test
    void constructor_rejectsNullEnvironmentId() {
        assertThatThrownBy(() -> new SandboxExecutionSuspendedEvent(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("environmentId");
    }
}
