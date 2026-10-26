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
        String suggestedNewBranch,
        PublishStatus status,
        int incomingCommits,
        int commitCount,
        boolean hasUncommittedChanges,
        ChangeSetSummaryDto changeSet,
        TargetDto target,
        String digest) {

    public enum PublishStatus {
        UP_TO_DATE,
        REBASE_REQUIRED
    }

    public record ChangeSetFileDto(String path, String status, int additions, int deletions) {
        public ChangeSetFileDto {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(status, "status is required");
        }
    }

    public record ChangeSetSummaryDto(int additions, int deletions, List<ChangeSetFileDto> files, String digest) {
        public ChangeSetSummaryDto {
            files = files != null ? List.copyOf(files) : List.of();
            Objects.requireNonNull(digest, "digest is required");
        }
    }

    public record TargetDto(TargetKind kind, Long prNumber, String branchName) {
        public enum TargetKind {
            OPEN_PR,
            UPDATE_PR,
            OPEN_PR_NEW_BRANCH
        }

        public static TargetDto openPr(String branchName) {
            return new TargetDto(TargetKind.OPEN_PR, null, branchName);
        }

        public static TargetDto updatePr(long prNumber, String branchName) {
            return new TargetDto(TargetKind.UPDATE_PR, prNumber, branchName);
        }

        public static TargetDto openPrNewBranch(String newBranchName) {
            return new TargetDto(TargetKind.OPEN_PR_NEW_BRANCH, null, newBranchName);
        }
    }

    public PublishCapabilitiesDto {
        Objects.requireNonNull(repositoryType, "repositoryType is required");
        Objects.requireNonNull(defaultBaseBranch, "defaultBaseBranch is required");
        stats = stats != null ? stats : new PublishStatsDto(0, 0, 0, 0, 0, false, "No changes");
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
