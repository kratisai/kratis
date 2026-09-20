package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.util.List;
import java.util.Objects;

public record PublishCapabilitiesDto(
        boolean supportsPullRequests,
        RepositoryType repositoryType,
        String defaultBaseBranch,
        PublishStatsDto stats,
        String suggestedTitle,
        String suggestedBody,
        String publishedBranch,
        Long publishedPrNumber,
        String publishedPrUrl,
        boolean newRepo,
        boolean canCreateRepository,
        String suggestedRepositoryName,
        List<RepositoryVisibility> visibilityOptions) {

    public PublishCapabilitiesDto {
        Objects.requireNonNull(repositoryType, "repositoryType is required");
        Objects.requireNonNull(defaultBaseBranch, "defaultBaseBranch is required");
        Objects.requireNonNull(stats, "stats is required");
        visibilityOptions = visibilityOptions != null ? List.copyOf(visibilityOptions) : List.of();
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
