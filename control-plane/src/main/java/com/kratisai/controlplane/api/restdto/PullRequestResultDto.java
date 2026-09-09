package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record PullRequestResultDto(long prNumber, String prUrl, String headBranch, String baseBranch) {
    public PullRequestResultDto {
        Objects.requireNonNull(prUrl, "prUrl is required");
        Objects.requireNonNull(headBranch, "headBranch is required");
        Objects.requireNonNull(baseBranch, "baseBranch is required");
    }
}
