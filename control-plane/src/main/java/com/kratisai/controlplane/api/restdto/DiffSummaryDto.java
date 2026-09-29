package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.api.wsdto.GitDiffStatus;
import java.util.List;
import java.util.Objects;

public record DiffSummaryDto(
        String baseCommit, String headCommit, int totalAdditions, int totalDeletions, List<DiffSummaryFileDto> files) {

    public record DiffSummaryFileDto(
            String path, GitDiffStatus status, int additions, int deletions, boolean isCollapsedByDefault) {
        public DiffSummaryFileDto {
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(status, "status is required");
        }
    }

    public DiffSummaryDto {
        Objects.requireNonNull(baseCommit, "baseCommit is required");
        Objects.requireNonNull(headCommit, "headCommit is required");
        files = files != null ? List.copyOf(files) : List.of();
    }
}
