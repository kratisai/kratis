package com.kratisai.controlplane.git.provider;

import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link RepoProvider} for {@link RepositoryType#BITBUCKET} repositories.
 *
 * <p>Listing and file reads go through the Bitbucket Cloud REST API using a {@code PAT} (app
 * password or workspace access token). Bitbucket repos authenticated with SSH keys are routed to
 * {@link GitProtocolRepoProvider} by the registry.
 */
@Component
public class BitbucketRepoProvider implements RepoProvider {

    private final BitbucketApiService bitbucketApiService;

    public BitbucketRepoProvider(BitbucketApiService bitbucketApiService) {
        this.bitbucketApiService = bitbucketApiService;
    }

    @Override
    public RepositoryType supportedType() {
        return RepositoryType.BITBUCKET;
    }

    @Override
    public String readFile(Repository repo, GitAuthMaterial auth, String path, String branch) {
        String token = auth.maybeToken()
                .orElseThrow(() ->
                        new IllegalStateException("Bitbucket file read requires a token, but no token was resolved"));
        String workspace = BitbucketApiService.extractWorkspace(repo.getUrl());
        String repoSlug = BitbucketApiService.extractRepoSlug(repo.getUrl());
        return bitbucketApiService.readFile(workspace, repoSlug, path, branch, token);
    }

    @Override
    public List<RemoteRepositoryDto> listRepositories(RepoCredential credential, GitAuthMaterial auth) {
        String token = auth.maybeToken()
                .orElseThrow(() -> new IllegalStateException(
                        "Bitbucket repository listing requires a token, but no token was resolved"));
        String workspace = credential.getMetadata().getBitbucketWorkspace().orElse(null);
        return bitbucketApiService.listRepositories(workspace, token);
    }

    @Override
    public PullRequestResultDto createPullRequest(
            Repository repo, GitAuthMaterial auth, CreatePullRequestCommand command) {
        String token = auth.maybeToken()
                .orElseThrow(() ->
                        new IllegalStateException("Bitbucket PR creation requires a token, but no token was resolved"));
        String workspace = BitbucketApiService.extractWorkspace(repo.getUrl());
        String repoSlug = BitbucketApiService.extractRepoSlug(repo.getUrl());
        return bitbucketApiService.createPullRequest(workspace, repoSlug, command, token);
    }

    @Override
    public boolean supportsRepositoryCreation() {
        return true;
    }

    @Override
    public RemoteRepositoryDto createRepository(
            RepoCredential credential, GitAuthMaterial auth, CreateRepositoryCommand command) {
        String token = auth.maybeToken()
                .orElseThrow(() -> new IllegalStateException(
                        "Bitbucket repository creation requires a token, but no token was resolved"));
        String workspace = credential
                .getMetadata()
                .getBitbucketWorkspace()
                .orElseThrow(() -> new IllegalStateException(
                        "Bitbucket repository creation requires a workspace in the credential metadata"));

        Optional<RemoteRepositoryDto> existing = bitbucketApiService.findRepository(workspace, command.name(), token);
        if (existing.isPresent()) {
            if (bitbucketApiService.repositoryHasCommits(workspace, command.name(), token)) {
                throw new RemoteRepositoryExistsException("Bitbucket repository " + workspace + "/" + command.name()
                        + " already exists and is not empty");
            }
            return existing.get();
        }
        return bitbucketApiService.createRepository(workspace, command, token);
    }
}
