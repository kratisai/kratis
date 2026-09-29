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
 * {@link RepoProvider} for {@link RepositoryType#GITLAB} repositories.
 *
 * <p>Both SaaS (gitlab.com) and self-hosted instances are supported. The instance base URL is taken
 * from the credential metadata ({@code gitlabUrl}) when present, otherwise derived from the
 * repository URL or defaulted to {@code https://gitlab.com}.
 */
@Component
public class GitLabRepoProvider implements RepoProvider {

    private final GitLabApiService gitLabApiService;

    public GitLabRepoProvider(GitLabApiService gitLabApiService) {
        this.gitLabApiService = gitLabApiService;
    }

    @Override
    public RepositoryType supportedType() {
        return RepositoryType.GITLAB;
    }

    @Override
    public String readFile(Repository repo, GitAuthMaterial auth, String path, String branch) {
        String token = auth.maybeToken()
                .orElseThrow(() ->
                        new IllegalStateException("GitLab file read requires a token, but no token was resolved"));
        String projectPath = GitLabApiService.extractProjectPath(repo.getUrl());
        return gitLabApiService.readFile(projectPath, path, branch, token, resolveBaseUrl(repo));
    }

    @Override
    public PullRequestResultDto createPullRequest(
            Repository repo, GitAuthMaterial auth, CreatePullRequestCommand command) {
        String token = auth.maybeToken()
                .orElseThrow(() ->
                        new IllegalStateException("GitLab PR creation requires a token, but no token was resolved"));
        String projectPath = GitLabApiService.extractProjectPath(repo.getUrl());
        return gitLabApiService.createPullRequest(projectPath, command, token, resolveBaseUrl(repo));
    }

    @Override
    public List<RemoteRepositoryDto> listRepositories(RepoCredential credential, GitAuthMaterial auth) {
        String token = auth.maybeToken()
                .orElseThrow(() -> new IllegalStateException(
                        "GitLab repository listing requires a token, but no token was resolved"));
        String baseUrl = resolveBaseUrl(credential);
        return gitLabApiService.listAvailableRepositories(token, baseUrl);
    }

    private String resolveBaseUrl(Repository repo) {
        return repo.getCredential() != null
                ? resolveBaseUrl(repo.getCredential())
                : GitLabApiService.extractBaseUrl(repo.getUrl());
    }

    private String resolveBaseUrl(RepoCredential credential) {
        return credential.getMetadata().getGitLabUrl().orElse("https://gitlab.com");
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
                        "GitLab repository creation requires a token, but no token was resolved"));
        String baseUrl = resolveBaseUrl(credential);

        Optional<String> group = credential.getMetadata().getGitLabGroup();
        Long namespaceId = null;
        String namespacePath;
        if (group.isPresent()) {
            namespaceId = gitLabApiService.getGroupId(group.get(), token, baseUrl);
            namespacePath = group.get() + "/" + command.name();
        } else {
            namespacePath = gitLabApiService.getAuthenticatedUsername(token, baseUrl) + "/" + command.name();
        }

        Optional<RemoteRepositoryDto> existing = gitLabApiService.findProject(namespacePath, token, baseUrl);
        if (existing.isPresent()) {
            if (gitLabApiService.projectHasCommits(namespacePath, token, baseUrl)) {
                throw new RemoteRepositoryExistsException(
                        "GitLab project " + namespacePath + " already exists and is not empty");
            }
            return existing.get();
        }
        return gitLabApiService.createRepository(namespaceId, command, token, baseUrl);
    }
}
