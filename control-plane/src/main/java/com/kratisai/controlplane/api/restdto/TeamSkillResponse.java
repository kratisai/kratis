package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.TeamSkill;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Team skill metadata")
public record TeamSkillResponse(
        @Schema(description = "Skill ID", example = "550e8400-e29b-41d4-a716-446655440020")
        UUID id,

        @Schema(description = "Skill name", example = "lint")
        String name,

        @Schema(description = "Skill description from SKILL.md frontmatter; tells agents when to use the skill")
        String description,

        @Schema(description = "Whether the skill is available to executions")
        boolean enabled,

        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt) {

    public static TeamSkillResponse from(TeamSkill skill) {
        return new TeamSkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.isEnabled(),
                skill.getCreatedAt(),
                skill.getUpdatedAt());
    }
}
