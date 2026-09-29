package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LlmUsageTest {

    @Test
    void withKey_shouldCreateUsageWithVirtualKey() {
        String key = "sk-test-key";

        LlmUsage usage = LlmUsage.withKey(key);

        assertThat(usage.getVirtualKey()).isEqualTo(key);
        assertThat(usage.getTotalSpend()).isEqualTo(0.0);
        assertThat(usage.getTotalTokens()).isEqualTo(0L);
        assertThat(usage.getPromptTokens()).isEqualTo(0L);
        assertThat(usage.getCompletionTokens()).isEqualTo(0L);
        assertThat(usage.getUsageLastUpdatedAt()).isNull();
    }

    @Test
    void apply_shouldUpdateAllFieldsFromSnapshot() {
        LlmUsage usage = new LlmUsage();
        usage.setVirtualKey("sk-test-key");
        LlmUsageSnapshot snapshot = new LlmUsageSnapshot(0.15, 300L, 200L, 100L);

        Instant beforeApply = Instant.now();
        usage.apply(snapshot);
        Instant afterApply = Instant.now();

        assertThat(usage.getVirtualKey()).isEqualTo("sk-test-key"); // unchanged
        assertThat(usage.getTotalSpend()).isEqualTo(0.15);
        assertThat(usage.getTotalTokens()).isEqualTo(300L);
        assertThat(usage.getPromptTokens()).isEqualTo(200L);
        assertThat(usage.getCompletionTokens()).isEqualTo(100L);
        assertThat(usage.getUsageLastUpdatedAt()).isNotNull();
        assertThat(usage.getUsageLastUpdatedAt()).isBetween(beforeApply, afterApply);
    }

    @Test
    void apply_shouldHandleNullValuesInSnapshot() {
        LlmUsage usage = new LlmUsage();
        LlmUsageSnapshot snapshot = new LlmUsageSnapshot(null, null, null, null);

        usage.apply(snapshot);

        assertThat(usage.getTotalSpend()).isEqualTo(0.0);
        assertThat(usage.getTotalTokens()).isEqualTo(0L);
        assertThat(usage.getPromptTokens()).isEqualTo(0L);
        assertThat(usage.getCompletionTokens()).isEqualTo(0L);
        assertThat(usage.getUsageLastUpdatedAt()).isNotNull();
    }

    @Test
    void apply_shouldSetTimestampToNow() {
        LlmUsage usage = new LlmUsage();
        LlmUsageSnapshot snapshot = new LlmUsageSnapshot(0.05, 100L, 60L, 40L);

        Instant beforeApply = Instant.now();
        usage.apply(snapshot);
        Instant afterApply = Instant.now();

        assertThat(usage.getUsageLastUpdatedAt()).isBetween(beforeApply, afterApply);
    }

    @Test
    void gettersAndSetters_shouldWorkCorrectly() {
        LlmUsage usage = new LlmUsage();

        usage.setVirtualKey("sk-new-key");
        usage.setTotalSpend(0.25);
        usage.setTotalTokens(500L);
        usage.setPromptTokens(300L);
        usage.setCompletionTokens(200L);
        Instant now = Instant.now();
        usage.setUsageLastUpdatedAt(now);

        assertThat(usage.getVirtualKey()).isEqualTo("sk-new-key");
        assertThat(usage.getTotalSpend()).isEqualTo(0.25);
        assertThat(usage.getTotalTokens()).isEqualTo(500L);
        assertThat(usage.getPromptTokens()).isEqualTo(300L);
        assertThat(usage.getCompletionTokens()).isEqualTo(200L);
        assertThat(usage.getUsageLastUpdatedAt()).isEqualTo(now);
    }
}
