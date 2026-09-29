package com.kratisai.controlplane.git.provider;

import java.util.Objects;

public record CreatePullRequestCommand(
        String headBranch, String baseBranch, String title, String description, boolean draft) {
    public CreatePullRequestCommand {
        Objects.requireNonNull(headBranch, "headBranch is required");
        Objects.requireNonNull(baseBranch, "baseBranch is required");
        Objects.requireNonNull(title, "title is required");
        description = description != null ? description : "";
    }
}
