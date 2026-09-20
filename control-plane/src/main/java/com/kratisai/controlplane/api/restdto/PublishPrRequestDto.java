package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.RepositoryVisibility;
import java.util.Objects;
import java.util.UUID;

public record PublishPrRequestDto(
        String branchName,
        String baseBranch,
        String title,
        String body,
        Boolean draft,
        Boolean squash,
        String repositoryName,
        RepositoryVisibility visibility,
        UUID credentialId) {
    public PublishPrRequestDto {
        Objects.requireNonNull(branchName, "branchName is required");
        Objects.requireNonNull(title, "title is required");
    }

    public PublishPrRequestDto(
            String branchName, String baseBranch, String title, String body, Boolean draft, Boolean squash) {
        this(branchName, baseBranch, title, body, draft, squash, null, null, null);
    }

    public PublishPrRequestDto(
            String branchName,
            String baseBranch,
            String title,
            String body,
            Boolean draft,
            Boolean squash,
            String repositoryName,
            RepositoryVisibility visibility) {
        this(branchName, baseBranch, title, body, draft, squash, repositoryName, visibility, null);
    }
}
