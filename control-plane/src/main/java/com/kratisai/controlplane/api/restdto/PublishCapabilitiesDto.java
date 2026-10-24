package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.time.Instant;
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
        boolean newRepo,
        boolean canCreateRepository,
        String suggestedRepositoryName,
        List<RepositoryVisibility> visibilityOptions,
        List<PublishedPrDto> publishedPrs,
        boolean previousPrMerged,
        String suggestedNewBranch) {

    public PublishCapabilitiesDto {
        Objects.requireNonNull(repositoryType, "repositoryType is required");
        Objects.requireNonNull(defaultBaseBranch, "defaultBaseBranch is required");
        Objects.requireNonNull(stats, "stats is required");
        visibilityOptions = visibilityOptions != null ? List.copyOf(visibilityOptions) : List.of();
        publishedPrs = publishedPrs != null ? List.copyOf(publishedPrs) : List.of();
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

    public record PublishedPrDto(
            Long prNumber,
            String prUrl,
            String headBranch,
            String baseBranch,
            PublishedPrStatus status,
            Instant createdAt) {
        public PublishedPrDto {
            Objects.requireNonNull(prNumber, "prNumber is required");
            Objects.requireNonNull(prUrl, "prUrl is required");
            Objects.requireNonNull(headBranch, "headBranch is required");
            Objects.requireNonNull(baseBranch, "baseBranch is required");
            Objects.requireNonNull(status, "status is required");
        }
    }
}
