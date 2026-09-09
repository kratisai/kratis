package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Team member information")
public record TeamMemberDto(
        @Schema(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID userId,

        @Schema(description = "User email", example = "user@example.com")
        String email,

        @Schema(description = "User display name", example = "John Doe")
        String displayName,

        @Schema(description = "Role in the team", example = "owner")
        String role) {}
