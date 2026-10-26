package com.kratisai.controlplane.git.provider;

import java.util.Objects;

/**
 * Observed live pull request / merge request state from upstream repository provider.
 */
public record PullRequestSnapshot(
        long number, PullRequestState state, String url, String headBranch, String baseBranch, String headSha) {
    public PullRequestSnapshot {
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(headBranch, "headBranch is required");
        Objects.requireNonNull(baseBranch, "baseBranch is required");
    }

    public enum PullRequestState {
        OPEN,
        MERGED,
        CLOSED
    }
}
