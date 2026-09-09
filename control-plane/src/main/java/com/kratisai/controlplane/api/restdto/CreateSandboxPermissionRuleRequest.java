package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

@Schema(description = "Request payload for creating a sandbox permission rule")
public record CreateSandboxPermissionRuleRequest(
        @Schema(description = "Command root pattern", example = "npm test")
        @NotBlank(message = "Command root is required")
        @JsonProperty("commandRoot")
        String commandRoot,

        @Schema(description = "Rule match type", example = "EXACT")
        @NotNull(message = "Rule type is required")
        @JsonProperty("ruleType")
        SandboxPermissionRuleType ruleType,

        @Schema(description = "Action when matched (defaults to ALLOW)", example = "ALLOW") @JsonProperty("action")
        SandboxPermissionAction action) {

    public CreateSandboxPermissionRuleRequest {
        Objects.requireNonNull(commandRoot, "Command root is required");
        if (commandRoot.isBlank()) {
            throw new IllegalArgumentException("Command root cannot be blank");
        }
        Objects.requireNonNull(ruleType, "Rule type is required");
        if (action == null) {
            action = SandboxPermissionAction.ALLOW;
        }
    }
}
