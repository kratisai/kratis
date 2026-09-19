package com.kratisai.controlplane.git.provider;

import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import java.util.List;

/**
 * Provider-specific content and repository-listing access, dispatched by {@link RepositoryType}.
 *
 * <p>Implementations receive an already-resolved {@link GitAuthMaterial} and must stay free of
 * credential-resolution logic. SSH-key and {@code GENERIC} repositories are served by
 * {@link GitProtocolRepoProvider}.
 */
public interface RepoProvider {
    RepositoryType supportedType();

    String readFile(Repository repo, GitAuthMaterial auth, String path, String branch);

    List<RemoteRepositoryDto> listRepositories(RepoCredential credential, GitAuthMaterial auth);

    default boolean supportsPullRequests() {
        return true;
    }

    PullRequestResultDto createPullRequest(Repository repo, GitAuthMaterial auth, CreatePullRequestCommand command);

    /** Repository creation is a host API operation, so SSH/GENERIC transports cannot support it. */
    default boolean supportsRepositoryCreation() {
        return false;
    }

    /**
     * Creates a remote repository for a new-repo execution, or reuses an existing empty one.
     * Implementations must fail when the name already has commits, so a retry cannot push onto
     * unrelated history.
     */
    default RemoteRepositoryDto createRepository(
            RepoCredential credential, GitAuthMaterial auth, CreateRepositoryCommand command) {
        throw new UnsupportedOperationException(
                "Repository creation is not supported for " + supportedType() + " repositories");
    }
}
