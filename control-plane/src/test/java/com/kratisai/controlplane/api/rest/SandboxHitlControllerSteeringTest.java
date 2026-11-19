package com.kratisai.controlplane.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit coverage for the post-resolution steering text of suggestion questions. */
class SandboxHitlControllerSteeringTest {

    @Test
    void followUp_appliesTheSuggestedPrompt() {
        assertThat(SandboxHitlController.suggestionSteeringTarget(Map.of("followUp", "/review unpushed")))
                .isEqualTo("The user applied the agent's suggested follow-up. Address exactly this: /review unpushed");
    }

    @Test
    void direction_steersAnAlternativeInsteadOfTheSuggestion() {
        assertThat(SandboxHitlController.suggestionSteeringTarget(Map.of("direction", "Write the docs first")))
                .isEqualTo("Instead of the agent's suggestion, the user directs: Write the docs first");
    }

    @Test
    void direction_winsOverFollowUp() {
        assertThat(SandboxHitlController.suggestionSteeringTarget(
                        Map.of("followUp", "/review unpushed", "direction", "Write the docs first")))
                .isEqualTo("Instead of the agent's suggestion, the user directs: Write the docs first");
    }

    @Test
    void blankAndMissingValues_produceNoTarget() {
        assertThat(SandboxHitlController.suggestionSteeringTarget(null)).isNull();
        assertThat(SandboxHitlController.suggestionSteeringTarget(Map.of())).isNull();
        assertThat(SandboxHitlController.suggestionSteeringTarget(Map.of("followUp", "   ")))
                .isNull();
        assertThat(SandboxHitlController.suggestionSteeringTarget(
                        Map.of("followUp", "   ", "direction", "Unblock staging")))
                .isEqualTo("Instead of the agent's suggestion, the user directs: Unblock staging");
    }

    @Test
    void nonStringContent_isIgnored() {
        assertThat(SandboxHitlController.suggestionSteeringTarget(Map.of("followUp", 42, "direction", true)))
                .isNull();
    }

    @Test
    void suggestionForm_requiresTheSidecarMarker() {
        assertThat(SandboxHitlController.isSuggestionForm(null)).isFalse();
        assertThat(SandboxHitlController.isSuggestionForm(Map.of("type", "object")))
                .isFalse();
        assertThat(SandboxHitlController.isSuggestionForm(Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "followUp", Map.of(),
                                "direction", Map.of()))))
                .isFalse();
        assertThat(SandboxHitlController.isSuggestionForm(Map.of("x-kratis-suggestion", true, "properties", Map.of())))
                .isTrue();
        assertThat(SandboxHitlController.isSuggestionForm(Map.of("x-kratis-suggestion", false)))
                .isFalse();
    }

    @Test
    void feedbackPrompts_areKindAware() {
        assertThat(SandboxHitlController.approvalFeedbackPrompt("use pnpm"))
                .isEqualTo("The user attached this guidance while responding to the approval request: use pnpm");
        assertThat(SandboxHitlController.questionFeedbackPrompt("use pnpm"))
                .isEqualTo("The user attached this guidance while responding to the agent's question: use pnpm");
    }

    @Test
    void dismissalNotice_isPresent() {
        assertThat(SandboxHitlController.DISMISSED_SUGGESTION_NOTICE).isNotBlank();
    }
}
