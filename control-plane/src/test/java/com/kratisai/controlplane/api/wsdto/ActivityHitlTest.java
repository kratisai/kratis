package com.kratisai.controlplane.api.wsdto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityHitlTest {

    @Test
    void fromSnapshot_copiesParseWarningAndFields() {
        UUID executionId = UUID.randomUUID();
        HitlRequestSnapshot snapshot = new HitlRequestSnapshot(
                executionId,
                "action-1",
                HitlKind.APPROVAL,
                "message-1",
                "echo $(whoami)",
                List.of(),
                "Complex syntax warning",
                "Title",
                "execute",
                List.of(),
                null,
                Map.of("key", "val"),
                List.of());

        ActivityHitl hitl = ActivityHitl.from(snapshot, HitlState.AWAITING_HUMAN);

        assertThat(hitl.kind()).isEqualTo(HitlKind.APPROVAL);
        assertThat(hitl.state()).isEqualTo(HitlState.AWAITING_HUMAN);
        assertThat(hitl.message()).isEqualTo("message-1");
        assertThat(hitl.parseWarning()).isEqualTo("Complex syntax warning");
        assertThat(hitl.title()).isEqualTo("Title");
        assertThat(hitl.toolKind()).isEqualTo("execute");
        assertThat(hitl.response()).isNull();
    }

    @Test
    void fromResolution_suppliesNullParseWarning() {
        UUID executionId = UUID.randomUUID();
        HitlResolution resolution = new HitlResolution(
                executionId,
                "action-1",
                HitlKind.APPROVAL,
                HitlResponse.APPROVED,
                "allow-1",
                Map.of(),
                HitlResolver.user(UUID.randomUUID(), "Alice"));

        ActivityHitl hitl = ActivityHitl.from(resolution);

        assertThat(hitl.kind()).isEqualTo(HitlKind.APPROVAL);
        assertThat(hitl.state()).isEqualTo(HitlState.RESOLVED);
        assertThat(hitl.message()).isEqualTo("action-1");
        assertThat(hitl.parseWarning()).isNull();
        assertThat(hitl.response()).isEqualTo(HitlResponse.APPROVED);
        assertThat(hitl.optionId()).isEqualTo("allow-1");
    }

    @Test
    void withResolution_preservesExistingParseWarning() {
        UUID executionId = UUID.randomUUID();
        HitlRequestSnapshot snapshot = new HitlRequestSnapshot(
                executionId,
                "action-1",
                HitlKind.APPROVAL,
                "message-1",
                "echo $(whoami)",
                List.of(),
                "Complex syntax warning",
                "Title",
                "execute",
                List.of(),
                null,
                null,
                null);

        ActivityHitl awaiting = ActivityHitl.from(snapshot, HitlState.AWAITING_HUMAN);

        HitlResolution resolution = new HitlResolution(
                executionId,
                "action-1",
                HitlKind.APPROVAL,
                HitlResponse.APPROVED,
                "allow-1",
                null,
                HitlResolver.user(UUID.randomUUID(), "Alice"));

        ActivityHitl resolved = awaiting.withResolution(resolution);

        assertThat(resolved.parseWarning()).isEqualTo("Complex syntax warning");
        assertThat(resolved.state()).isEqualTo(HitlState.RESOLVED);
        assertThat(resolved.response()).isEqualTo(HitlResponse.APPROVED);
        assertThat(resolved.kind()).isEqualTo(HitlKind.APPROVAL);
        assertThat(resolved.message()).isEqualTo("message-1");
    }

    @Test
    void withResolution_fallsBackWhenKindOrMessageNull() {
        ActivityHitl blank =
                new ActivityHitl(null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        UUID executionId = UUID.randomUUID();
        HitlResolution resolution = new HitlResolution(
                executionId,
                "fallback-action",
                HitlKind.APPROVAL,
                HitlResponse.APPROVED,
                "allow-1",
                null,
                HitlResolver.user(UUID.randomUUID(), "Alice"));

        ActivityHitl resolved = blank.withResolution(resolution);

        assertThat(resolved.kind()).isEqualTo(HitlKind.APPROVAL);
        assertThat(resolved.message()).isEqualTo("fallback-action");
    }

    @Test
    void hitlRequestSnapshot_requiresMandatoryFields() {
        UUID executionId = UUID.randomUUID();
        assertThatNullPointerException()
                .isThrownBy(() -> new HitlRequestSnapshot(
                        null, "a", HitlKind.APPROVAL, "m", null, null, null, null, null, null, null, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new HitlRequestSnapshot(
                        executionId,
                        null,
                        HitlKind.APPROVAL,
                        "m",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        assertThatNullPointerException()
                .isThrownBy(() -> new HitlRequestSnapshot(
                        executionId, "a", null, "m", null, null, null, null, null, null, null, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new HitlRequestSnapshot(
                        executionId,
                        "a",
                        HitlKind.APPROVAL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }
}
