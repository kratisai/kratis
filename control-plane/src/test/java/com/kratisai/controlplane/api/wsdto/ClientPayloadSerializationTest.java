package com.kratisai.controlplane.api.wsdto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionActivityResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPayloadSerializationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-20T17:32:12.672Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executionActivityResult_omitsNullActionId() {
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.COMMAND,
                "Running tests",
                null,
                ActivityStatus.COMPLETED,
                null,
                CREATED_AT.toString()));

        assertThat(json.has("actionId")).isFalse();
        assertThat(json.has("detail")).isFalse();
        assertThat(json.get("activityType").asText()).isEqualTo("COMMAND");
        assertThat(json.get("status").asText()).isEqualTo("completed");
        assertThat(json.get("createdAt").asText()).isEqualTo("2026-10-20T17:32:12.672Z");
    }

    @Test
    void executionActivityResult_serializesPresentActionIdAndDetail() {
        ActivityDetail detail = ActivityDetail.forTool(ActivityKind.EXECUTE, "Running tests", 0, false);
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.RESEARCH,
                "Reading",
                "tool-call-1",
                ActivityStatus.IN_PROGRESS,
                detail,
                CREATED_AT.toString()));

        assertThat(json.get("actionId").asText()).isEqualTo("tool-call-1");
        assertThat(json.get("status").asText()).isEqualTo("in_progress");
        assertThat(json.path("detail").path("kind").asText()).isEqualTo("execute");
        assertThat(json.path("detail").path("exitCode").asInt()).isEqualTo(0);
    }

    @Test
    void executionActivityResult_serializesStructuredPlanAndOmitsEmptyPlan() {
        ActivityDetail withPlan = ActivityDetail.forPlan(
                List.of(new PlanEntry("Setup", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS)));
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.PLAN,
                "Agent plan updated",
                "plan",
                ActivityStatus.IN_PROGRESS,
                withPlan,
                CREATED_AT.toString()));

        assertThat(json.get("activityType").asText()).isEqualTo("PLAN");
        JsonNode plan = json.path("detail").path("plan");
        assertThat(plan.isArray()).isTrue();
        assertThat(plan.get(0).get("content").asText()).isEqualTo("Setup");
        assertThat(plan.get(0).get("priority").asText()).isEqualTo("high");
        assertThat(plan.get(0).get("status").asText()).isEqualTo("in_progress");

        ActivityDetail emptyPlan = ActivityDetail.forPlan(List.of());
        JsonNode empty = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.PLAN,
                "Agent plan updated",
                "plan",
                ActivityStatus.IN_PROGRESS,
                emptyPlan,
                CREATED_AT.toString()));
        assertThat(empty.path("detail").has("plan")).isFalse();
    }

    @Test
    void executionActivityResult_serializesPublishDetail() {
        ActivityDetail publishDetail = ActivityDetail.forPublish(
                42L, "https://github.com/test/repo/pull/42", "feature", "main", "abc1234", false, "GITHUB");
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.PUBLISH,
                "Published changes: Pull Request #42 created to merge feature into main",
                "pub:pr:repo-1:42",
                ActivityStatus.COMPLETED,
                publishDetail,
                CREATED_AT.toString()));

        assertThat(json.get("activityType").asText()).isEqualTo("PUBLISH");
        assertThat(json.get("status").asText()).isEqualTo("completed");
        assertThat(json.path("detail").path("prNumber").asInt()).isEqualTo(42);
        assertThat(json.path("detail").path("prUrl").asText()).isEqualTo("https://github.com/test/repo/pull/42");
        assertThat(json.path("detail").path("headBranch").asText()).isEqualTo("feature");
        assertThat(json.path("detail").path("baseBranch").asText()).isEqualTo("main");
        assertThat(json.path("detail").path("commitSha").asText()).isEqualTo("abc1234");
        assertThat(json.path("detail").path("isUpdate").asBoolean()).isFalse();
        assertThat(json.path("detail").path("providerType").asText()).isEqualTo("GITHUB");
    }

    @Test
    void executionDiffChangedResult_serializesExpectedFields() {
        UUID execId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        JsonNode json = objectMapper.valueToTree(new ClientPayload.ExecutionDiffChangedResult(execId, chatId, teamId));

        assertThat(json.get("type").asText()).isEqualTo("execution_diff_changed");
        assertThat(json.get("executionId").asText()).isEqualTo(execId.toString());
        assertThat(json.get("chatId").asText()).isEqualTo(chatId.toString());
        assertThat(json.get("teamId").asText()).isEqualTo(teamId.toString());
    }

    @Test
    void planningStatusResult_serializesAndDeserializesExpectedFields() throws Exception {
        UUID chatId = UUID.randomUUID();
        ClientPayload.PlanningStatusResult payload = new ClientPayload.PlanningStatusResult(
                chatId, "paused", "max_iterations", java.util.Map.of("directive", "Continue planning"));

        JsonNode json = objectMapper.valueToTree(payload);
        assertThat(json.get("type").asText()).isEqualTo("planning_status");
        assertThat(json.get("chatId").asText()).isEqualTo(chatId.toString());
        assertThat(json.get("status").asText()).isEqualTo("paused");
        assertThat(json.get("reason").asText()).isEqualTo("max_iterations");
        assertThat(json.get("metadata").get("directive").asText()).isEqualTo("Continue planning");

        ClientPayload.PlanningStatusResult deserialized =
                objectMapper.treeToValue(json, ClientPayload.PlanningStatusResult.class);
        assertThat(deserialized.chatId()).isEqualTo(chatId);
        assertThat(deserialized.status()).isEqualTo("paused");
        assertThat(deserialized.reason()).isEqualTo("max_iterations");
        assertThat(deserialized.metadata()).containsEntry("directive", "Continue planning");
    }
}
