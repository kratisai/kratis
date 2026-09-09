package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for creating a new team")
public record CreateTeamRequest(
        @Schema(description = "Team name", example = "My Team")
        @NotBlank(message = "Team name is required")
        @Size(max = 255, message = "Team name must be at most 255 characters")
        String name,

        @Schema(description = "Team description", example = "A team for my project")
        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description) {}
