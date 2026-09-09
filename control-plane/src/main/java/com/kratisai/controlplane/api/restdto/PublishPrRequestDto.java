package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record PublishPrRequestDto(
        String branchName, String baseBranch, String title, String body, Boolean draft, Boolean squash) {
    public PublishPrRequestDto {
        Objects.requireNonNull(branchName, "branchName is required");
        Objects.requireNonNull(title, "title is required");
    }

    public PublishPrRequestDto(String branchName, String baseBranch, String title, String body, Boolean draft) {
        this(branchName, baseBranch, title, body, draft, false);
    }
}
