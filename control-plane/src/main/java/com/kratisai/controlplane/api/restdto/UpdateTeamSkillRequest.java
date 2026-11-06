package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Partial update of team skill availability")
public record UpdateTeamSkillRequest(
        @Schema(description = "Whether the skill is available to executions", example = "true")
        Boolean enabled) {}
