package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.TeamWikiBuildMode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to manually rebuild the team wiki")
public record TeamWikiRebuildRequest(
        @Schema(description = "Build mode (INCREMENTAL or FULL)", nullable = true)
        TeamWikiBuildMode mode) {}
