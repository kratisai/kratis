package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record PublishCapabilitiesDto(
        boolean supportsPullRequests,
        String repositoryType,
        String defaultBaseBranch,
        PublishStatsDto stats,
        String suggestedTitle,
        String suggestedBody,
        String publishedBranch,
        Long publishedPrNumber,
        String publishedPrUrl) {

    public PublishCapabilitiesDto {
        Objects.requireNonNull(repositoryType, "repositoryType is required");
        Objects.requireNonNull(defaultBaseBranch, "defaultBaseBranch is required");
        Objects.requireNonNull(stats, "stats is required");
    }

    public record PublishStatsDto(
            int commitsAhead,
            int stagedFiles,
            int unstagedFiles,
            int additions,
            int deletions,
            boolean hasChanges,
            String formattedSummary) {
        public PublishStatsDto {
            Objects.requireNonNull(formattedSummary, "formattedSummary is required");
        }
    }
}
