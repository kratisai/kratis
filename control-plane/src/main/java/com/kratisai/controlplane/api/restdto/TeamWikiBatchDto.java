package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "Team wiki build batch summary")
public record TeamWikiBatchDto(
        @Schema(description = "Batch ID") UUID id,
        @Schema(description = "Build status") IngestionStatus status,

        @Schema(description = "Whether this batch is the currently active team wiki")
        boolean isActive,

        @Schema(description = "Build mode (FULL or INCREMENTAL)")
        TeamWikiBuildMode buildMode,

        @Schema(description = "Trigger kind (AUTOMATIC or MANUAL)")
        TeamWikiTriggerKind triggerKind,

        @Schema(description = "Number of repositories covered in snapshot")
        int repoCount,

        @Schema(description = "Start timestamp") Instant startedAt,

        @Schema(description = "Completion timestamp", nullable = true)
        Instant completedAt,

        @Schema(description = "Error message if failed", nullable = true)
        String errorMessage,

        @Schema(description = "Total LLM tool calls", nullable = true)
        Long totalToolCalls) {
    public TeamWikiBatchDto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(buildMode, "buildMode");
        Objects.requireNonNull(triggerKind, "triggerKind");
    }
}
