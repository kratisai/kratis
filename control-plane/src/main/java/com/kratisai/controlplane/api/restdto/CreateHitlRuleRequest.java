package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kratisai.controlplane.api.wsdto.ToolKind;
import com.kratisai.controlplane.model.HitlRuleAction;
import com.kratisai.controlplane.model.HitlRuleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload for creating a HITL rule (always allow or always block a command root)")
public record CreateHitlRuleRequest(
        @Schema(description = "Command root pattern; must match on whole leading words", example = "npm run test")
        @NotBlank(message = "Command root is required")
        @JsonProperty("commandRoot")
        String commandRoot,

        @Schema(description = "Rule match type (defaults to PREFIX_WILD)", example = "EXACT") @JsonProperty("ruleType")
        HitlRuleType ruleType,

        @Schema(description = "Action when matched (defaults to ALLOW)", example = "ALLOW") @JsonProperty("action")
        HitlRuleAction action) {

    private static final int MAX_ROOT_LENGTH = 500;

    public CreateHitlRuleRequest {
        if (commandRoot == null) {
            throw new IllegalArgumentException("Command root is required");
        }
        commandRoot = commandRoot.strip().replaceAll("\\s+", " ");
        if (commandRoot.isEmpty()) {
            throw new IllegalArgumentException("Command root cannot be blank");
        }
        if (commandRoot.length() > MAX_ROOT_LENGTH) {
            throw new IllegalArgumentException("Command root too long");
        }
        if (ruleType == null) {
            ruleType = HitlRuleType.PREFIX_WILD;
        }
        if (action == null) {
            action = HitlRuleAction.ALLOW;
        }
        if (ruleType == HitlRuleType.TOOL_KIND) {
            String requested = commandRoot;
            commandRoot = ToolKind.fromWireValue(requested)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tool kind: " + requested))
                    .wireValue();
        }
    }
}
