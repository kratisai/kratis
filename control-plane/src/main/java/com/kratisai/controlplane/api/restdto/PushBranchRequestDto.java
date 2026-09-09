package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record PushBranchRequestDto(String branchName, String commitMessage, Boolean squash) {
    public PushBranchRequestDto {
        Objects.requireNonNull(branchName, "branchName is required");
    }

    public PushBranchRequestDto(String branchName, String commitMessage) {
        this(branchName, commitMessage, false);
    }
}
