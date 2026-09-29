package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Request payload for adding a member to a team")
public record AddTeamMemberRequest(
        @Schema(description = "Email of the user to add", example = "other@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @Schema(description = "Role to assign", example = "member")
        @NotBlank(message = "Role is required")
        @Pattern(regexp = "owner|admin|member", message = "Role must be owner, admin, or member")
        String role) {}
