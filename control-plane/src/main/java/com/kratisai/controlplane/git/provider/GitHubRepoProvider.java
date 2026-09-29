package com.kratisai.controlplane.git.provider;

import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@link RepoProvider} for {@link RepositoryType#GITHUB} repositories.
 *
 * <p>Both {@code GITHUB_APP} (installation-token exchange) and {@code PAT} credentials resolve to a
 * token via {@code GitCredentialResolver}, so {@code readFile} works for both. Repository listing
 * supports GitHub App installations and {@code PAT} credentials (via the user repositories API).
 */
@Component
public class GitHubRepoProvider implements RepoProvider {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("github\\.com[:/]([^/]+)/([^/.]+)(?:\\.git)?$");

    private final GitHubApiService gitHubApiService;

    public GitHubRepoProvider(GitHubApiService gitHubApiService) {
        this.gitHubApiService = gitHubApiService;
    }

    @Override
    public RepositoryType supportedType() {
        return RepositoryType.GITHUB;
    }

    @Override
    public String readFile(Repository repo, GitAuthMaterial auth, String path, String branch) {
        String owner = extractOwner(repo.getUrl());
        String repoSlug = extractRepoName(repo.getUrl());
        String token = auth.maybeToken()
                .orElseThrow(() ->
                        new IllegalStateException("GitHub file read requires a token, but no token was resolved"));

        return gitHubApiService.readFile(owner, repoSlug, path, branch, token);
    }

    @Override
    public List<RemoteRepositoryDto> listRepositories(RepoCredential credential, GitAuthMaterial auth) {
        String installationId = credential.getMetadata().getInstallationId().orElse(null);
        if (installationId != null && !installationId.isBlank()) {
            return gitHubApiService.listAvailableRepositories(installationId);
        }

        String token = auth.maybeToken()
                .orElseThrow(
                        () -> new IllegalStateException(
                                "GitHub repository listing requires either an installationId or a token, but neither was resolved"));
        return gitHubApiService.listUserRepositories(token);
    }

    @Override
    public PullRequestResultDto createPullRequest(
            Repository repo, GitAuthMaterial auth, CreatePullRequestCommand command) {
        String owner = extractOwner(repo.getUrl());
        String repoSlug = extractRepoName(repo.getUrl());
        String token = auth.maybeToken()
                .orElseThrow(() ->
                        new IllegalStateException("GitHub PR creation requires a token, but no token was resolved"));

        return gitHubApiService.createPullRequest(owner, repoSlug, command, token);
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
                        "GitHub repository creation requires a token, but no token was resolved"));

        String installationId = credential.getMetadata().getInstallationId().orElse(null);
        boolean appInstallation = installationId != null && !installationId.isBlank();
        GitHubApiService.GitHubAccount account = appInstallation
                ? gitHubApiService.getInstallationAccount(installationId)
                : gitHubApiService.getAuthenticatedAccount(token);
        if (appInstallation && !account.organization()) {
            throw new IllegalStateException("GitHub App installations on personal accounts cannot create repositories; "
                    + "use a PAT or install the app on an organization");
        }

        Optional<RemoteRepositoryDto> existing =
                gitHubApiService.findRepository(account.login(), command.name(), token);
        if (existing.isPresent()) {
            if (gitHubApiService.repositoryHasCommits(account.login(), command.name(), token)) {
                throw new RemoteRepositoryExistsException(
                        "Repository " + account.login() + "/" + command.name() + " already exists and is not empty");
            }
            return existing.get();
        }
        return gitHubApiService.createRepository(account.login(), account.organization(), command, token);
    }

    private String extractOwner(String repoUrl) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(Objects.requireNonNull(repoUrl, "repoUrl"));
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Unable to extract owner from repository URL: " + repoUrl);
    }

    private String extractRepoName(String repoUrl) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(Objects.requireNonNull(repoUrl, "repoUrl"));
        if (matcher.find()) {
            return matcher.group(2);
        }
        throw new IllegalArgumentException("Unable to extract repo name from repository URL: " + repoUrl);
    }
}
