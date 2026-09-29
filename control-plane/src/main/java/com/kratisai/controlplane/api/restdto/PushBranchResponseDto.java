package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record PushBranchResponseDto(String branchName, String commitSha, String remoteRef, String status) {
    public PushBranchResponseDto {
        Objects.requireNonNull(branchName, "branchName is required");
        Objects.requireNonNull(commitSha, "commitSha is required");
        Objects.requireNonNull(status, "status is required");
    }
}
