package com.kratisai.controlplane.api.wsdto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionActivityResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPayloadSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executionActivityResult_omitsNullActionId() {
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(), ActivityType.COMMAND, "Running tests", ActivityStatus.COMPLETED));

        assertThat(json.has("actionId")).isFalse();
        assertThat(json.has("detail")).isFalse();
        assertThat(json.get("activityType").asText()).isEqualTo("COMMAND");
        assertThat(json.get("status").asText()).isEqualTo("completed");
    }

    @Test
    void executionActivityResult_serializesPresentActionIdAndDetail() {
        ActivityDetail detail = new ActivityDetail(
                ActivityKind.EXECUTE,
                "Running tests",
                null,
                java.util.Map.of("command", "go build"),
                null,
                null,
                0,
                false,
                null,
                null,
                null,
                null,
                null,
                null);
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.RESEARCH,
                "Reading",
                "tool-call-1",
                ActivityStatus.IN_PROGRESS,
                detail));

        assertThat(json.get("actionId").asText()).isEqualTo("tool-call-1");
        assertThat(json.get("status").asText()).isEqualTo("in_progress");
        assertThat(json.path("detail").path("kind").asText()).isEqualTo("execute");
        assertThat(json.path("detail").path("exitCode").asInt()).isEqualTo(0);
    }

    @Test
    void executionActivityResult_serializesStructuredPlanAndOmitsEmptyPlan() {
        ActivityDetail withPlan = new ActivityDetail(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.List.of(new PlanEntry("Setup", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS)),
                null,
                null);
        JsonNode json = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.PLAN,
                "Agent plan updated",
                "plan",
                ActivityStatus.IN_PROGRESS,
                withPlan));

        assertThat(json.get("activityType").asText()).isEqualTo("PLAN");
        JsonNode plan = json.path("detail").path("plan");
        assertThat(plan.isArray()).isTrue();
        assertThat(plan.get(0).get("content").asText()).isEqualTo("Setup");
        assertThat(plan.get(0).get("priority").asText()).isEqualTo("high");
        assertThat(plan.get(0).get("status").asText()).isEqualTo("in_progress");

        ActivityDetail emptyPlan = new ActivityDetail(
                null, null, null, null, null, null, null, null, null, null, null, java.util.List.of(), null, null);
        JsonNode empty = objectMapper.valueToTree(new ExecutionActivityResult(
                UUID.randomUUID(),
                ActivityType.PLAN,
                "Agent plan updated",
                "plan",
                ActivityStatus.IN_PROGRESS,
                emptyPlan));
        assertThat(empty.path("detail").has("plan")).isFalse();
    }

    @Test
    void executionHitlRequiredResult_omitsNullOptionalFields() {
        JsonNode json = objectMapper.valueToTree(new ExecutionHitlRequiredResult(
                UUID.randomUUID(), "tool-call-1", HitlKind.APPROVAL, "Allow rm -rf /?"));

        assertThat(json.has("command")).isFalse();
        assertThat(json.has("options")).isFalse();
        assertThat(json.get("kind").asText()).isEqualTo("approval");
    }

    @Test
    void executionHitlRequiredResult_serializesPresentCommandAndOptions() {
        JsonNode json = objectMapper.valueToTree(new ExecutionHitlRequiredResult(
                UUID.randomUUID(),
                "tool-call-1",
                HitlKind.APPROVAL,
                "Allow rm -rf /?",
                "rm -rf /",
                "Remove",
                "execute",
                java.util.List.of(new PermissionOption("allow-once", "Allow once", ApprovalOptionKind.ALLOW_ONCE)),
                null,
                null));

        assertThat(json.get("command").asText()).isEqualTo("rm -rf /");
        assertThat(json.path("options").get(0).get("optionId").asText()).isEqualTo("allow-once");
    }

    @Test
    void executionHitlResolvedResult_keepsNullFieldsForResolvedByMetadata() {
        JsonNode json = objectMapper.valueToTree(new ExecutionHitlResolvedResult(
                UUID.randomUUID(),
                "tool-call-1",
                HitlKind.APPROVAL,
                HitlResponse.APPROVED,
                "allow-once",
                null,
                null,
                null));

        assertThat(json.has("resolvedByUserId")).isTrue();
        assertThat(json.get("resolvedByUserId").isNull()).isTrue();
        assertThat(json.has("resolvedByDisplayName")).isTrue();
        assertThat(json.get("resolvedByDisplayName").isNull()).isTrue();
    }
}
