package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Sandbox permission rule representation")
public record SandboxPermissionRuleDto(
        @Schema(description = "Rule ID", example = "550e8400-e29b-41d4-a716-446655440010")
        UUID id,

        @Schema(description = "Team ID", example = "550e8400-e29b-41d4-a716-446655440011")
        UUID teamId,

        @Schema(description = "Command root pattern", example = "npm test")
        String commandRoot,

        @Schema(description = "Rule match type", example = "EXACT")
        SandboxPermissionRuleType ruleType,

        @Schema(description = "Action when matched", example = "ALLOW")
        SandboxPermissionAction action,

        @Schema(description = "Creator User ID", example = "550e8400-e29b-41d4-a716-446655440012")
        UUID createdByUserId,

        @Schema(description = "Creator display name", example = "Alice")
        String createdByName,

        @Schema(description = "Creation timestamp", example = "2026-09-06T12:00:00Z")
        Instant createdAt) {

    public static SandboxPermissionRuleDto from(SandboxPermissionRule rule) {
        return new SandboxPermissionRuleDto(
                rule.getId(),
                rule.getTeam() != null ? rule.getTeam().getId() : null,
                rule.getCommandRoot(),
                rule.getRuleType(),
                rule.getAction(),
                rule.getCreatedBy() != null ? rule.getCreatedBy().getId() : null,
                rule.getCreatedBy() != null ? rule.getCreatedBy().getDisplayName() : null,
                rule.getCreatedAt());
    }
}
