package com.kratisai.controlplane.api.wsdto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WsDtoEnumTest {

    @Test
    void stopReason_valuesAndLookup() {
        assertThat(StopReason.END_TURN.getValue()).isEqualTo("end_turn");
        assertThat(StopReason.fromString("end_turn")).isEqualTo(StopReason.END_TURN);
        assertThat(StopReason.fromString("MAX_TOKENS")).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(StopReason.fromString("cancelled")).isEqualTo(StopReason.CANCELLED);
        assertThatThrownBy(() -> StopReason.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stop reason");
    }

    @Test
    void outputStream_valuesAndLookup() {
        assertThat(OutputStream.STDOUT.getValue()).isEqualTo("stdout");
        assertThat(OutputStream.STDERR.getValue()).isEqualTo("stderr");
        assertThat(OutputStream.fromString("stdout")).isEqualTo(OutputStream.STDOUT);
        assertThat(OutputStream.fromString("STDERR")).isEqualTo(OutputStream.STDERR);
        assertThatThrownBy(() -> OutputStream.fromString("stdin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stream must be stdout or stderr");
    }

    @Test
    void checkoutStatus_valuesAndLookup() {
        assertThat(CheckoutStatus.SUCCESS.getValue()).isEqualTo("success");
        assertThat(CheckoutStatus.FAILED.getValue()).isEqualTo("failed");
        assertThat(CheckoutStatus.fromString("success")).isEqualTo(CheckoutStatus.SUCCESS);
        assertThat(CheckoutStatus.fromString("FAILED")).isEqualTo(CheckoutStatus.FAILED);
        assertThatThrownBy(() -> CheckoutStatus.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown checkout status");
    }

    @Test
    void activityType_valuesAndLookup() {
        assertThat(ActivityType.THINKING.getValue()).isEqualTo("THINKING");
        assertThat(ActivityType.fromString("RESEARCH")).isEqualTo(ActivityType.RESEARCH);
        assertThat(ActivityType.fromString("edited")).isEqualTo(ActivityType.EDITED);
        assertThat(ActivityType.fromString("command")).isEqualTo(ActivityType.COMMAND);
        assertThat(ActivityType.MESSAGE.getValue()).isEqualTo("MESSAGE");
        assertThat(ActivityType.fromString("message")).isEqualTo(ActivityType.MESSAGE);
        assertThatThrownBy(() -> ActivityType.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown activity type");
    }

    @Test
    void activityStatus_valuesAndLookup() {
        assertThat(ActivityStatus.PENDING.getValue()).isEqualTo("pending");
        assertThat(ActivityStatus.IN_PROGRESS.getValue()).isEqualTo("in_progress");
        assertThat(ActivityStatus.COMPLETED.getValue()).isEqualTo("completed");
        assertThat(ActivityStatus.FAILED.getValue()).isEqualTo("failed");
        assertThat(ActivityStatus.fromString("pending")).isEqualTo(ActivityStatus.PENDING);
        assertThat(ActivityStatus.fromString("IN_PROGRESS")).isEqualTo(ActivityStatus.IN_PROGRESS);
        assertThat(ActivityStatus.fromString("completed")).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(ActivityStatus.fromString("failed")).isEqualTo(ActivityStatus.FAILED);
        assertThatThrownBy(() -> ActivityStatus.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown activity status");
    }

    @Test
    void activityKind_valuesAndLookup() {
        assertThat(ActivityKind.READ.getValue()).isEqualTo("read");
        assertThat(ActivityKind.EDIT.getValue()).isEqualTo("edit");
        assertThat(ActivityKind.DELETE.getValue()).isEqualTo("delete");
        assertThat(ActivityKind.MOVE.getValue()).isEqualTo("move");
        assertThat(ActivityKind.SEARCH.getValue()).isEqualTo("search");
        assertThat(ActivityKind.EXECUTE.getValue()).isEqualTo("execute");
        assertThat(ActivityKind.THINK.getValue()).isEqualTo("think");
        assertThat(ActivityKind.FETCH.getValue()).isEqualTo("fetch");
        assertThat(ActivityKind.SWITCH_MODE.getValue()).isEqualTo("switch_mode");
        assertThat(ActivityKind.OTHER.getValue()).isEqualTo("other");
        assertThat(ActivityKind.fromString("EXECUTE")).isEqualTo(ActivityKind.EXECUTE);
        assertThat(ActivityKind.fromString("switch_mode")).isEqualTo(ActivityKind.SWITCH_MODE);
        assertThatThrownBy(() -> ActivityKind.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown activity kind");
    }

    @Test
    void approvalOptionKind_valuesAndLookup() {
        assertThat(ApprovalOptionKind.ALLOW_ONCE.getValue()).isEqualTo("allow_once");
        assertThat(ApprovalOptionKind.ALLOW_ALWAYS.getValue()).isEqualTo("allow_always");
        assertThat(ApprovalOptionKind.REJECT_ONCE.getValue()).isEqualTo("reject_once");
        assertThat(ApprovalOptionKind.REJECT_ALWAYS.getValue()).isEqualTo("reject_always");
        assertThat(ApprovalOptionKind.fromString("allow_once")).isEqualTo(ApprovalOptionKind.ALLOW_ONCE);
        assertThat(ApprovalOptionKind.fromString("REJECT_ALWAYS")).isEqualTo(ApprovalOptionKind.REJECT_ALWAYS);
        assertThatThrownBy(() -> ApprovalOptionKind.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown approval option kind");
    }
}
