package com.kratisai.controlplane.model.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// The null-rejection tests deliberately pass null to a constructor whose
// parameters are non-null, which SpotBugs flags as NP_NULL_PARAM_DEREF_NONVIRTUAL.
@SuppressFBWarnings("NP_NULL_PARAM_DEREF_NONVIRTUAL")
class ExecutionStatusChangedEventTest {

    @Test
    void constructor_acceptsNonNullIds() {
        UUID teamId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        ExecutionStatusChangedEvent event = new ExecutionStatusChangedEvent(teamId, chatId, executionId);

        assertThat(event.teamId()).isEqualTo(teamId);
        assertThat(event.chatId()).isEqualTo(chatId);
        assertThat(event.executionId()).isEqualTo(executionId);
    }

    @Test
    void constructor_rejectsNullTeamId() {
        assertThatThrownBy(() -> new ExecutionStatusChangedEvent(null, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("teamId");
    }

    @Test
    void constructor_rejectsNullChatId() {
        assertThatThrownBy(() -> new ExecutionStatusChangedEvent(UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chatId");
    }

    @Test
    void constructor_rejectsNullExecutionId() {
        assertThatThrownBy(() -> new ExecutionStatusChangedEvent(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("executionId");
    }
}
