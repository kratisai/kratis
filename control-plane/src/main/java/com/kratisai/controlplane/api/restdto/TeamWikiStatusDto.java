package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSchedulerService.DeferralReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Schema(description = "Team wiki status, staleness, and recent batch history")
public record TeamWikiStatusDto(
        @Schema(description = "Number of active ingested repositories in the team")
        int ingestedRepoCount,

        @Schema(description = "Whether structural changes are pending since the active team wiki")
        boolean dirty,

        @Schema(description = "Number of repos re-ingested without structural changes since last build")
        int nonStructuralChangedRepoCount,

        @Schema(description = "Reason automatic rebuild is currently deferred, if any")
        DeferralReason deferralReason,

        @Schema(description = "Earliest time the deferred automatic rebuild becomes eligible", nullable = true)
        Instant nextEligibleAt,

        @Schema(description = "Currently active team wiki batch", nullable = true)
        TeamWikiBatchDto activeBatch,

        @Schema(description = "Most recent team wiki batch (including in-progress or failed)", nullable = true)
        TeamWikiBatchDto latestBatch,

        @Schema(description = "Recent team wiki batches") List<TeamWikiBatchDto> recentBatches) {
    public TeamWikiStatusDto {
        Objects.requireNonNull(deferralReason, "deferralReason");
        recentBatches = recentBatches == null ? List.of() : List.copyOf(recentBatches);
    }
}
