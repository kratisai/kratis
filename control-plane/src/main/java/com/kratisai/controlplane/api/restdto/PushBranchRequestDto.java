package com.kratisai.controlplane.api.restdto;

import java.util.Objects;
import java.util.UUID;

public record PushBranchRequestDto(String branchName, String commitMessage, Boolean squash, UUID credentialId) {
    public PushBranchRequestDto {
        Objects.requireNonNull(branchName, "branchName is required");
    }

    public PushBranchRequestDto(String branchName, String commitMessage, Boolean squash) {
        this(branchName, commitMessage, squash, null);
    }
}
