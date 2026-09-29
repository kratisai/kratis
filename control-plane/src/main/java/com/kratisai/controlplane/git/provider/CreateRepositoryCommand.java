package com.kratisai.controlplane.git.provider;

import com.kratisai.controlplane.model.RepositoryVisibility;
import java.util.Objects;

/**
 * Request to create a remote repository. {@code name} is the bare repository name; the owner or
 * namespace is derived from the credential by each provider.
 */
public record CreateRepositoryCommand(String name, RepositoryVisibility visibility, String defaultBranch) {
    public CreateRepositoryCommand {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(visibility, "visibility is required");
        Objects.requireNonNull(defaultBranch, "defaultBranch is required");
    }
}
