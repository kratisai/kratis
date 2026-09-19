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
 * {@link RepoProvider} for {@link RepositoryType#AZURE} repositories (Azure DevOps).
 *
 * <p>Supports both Azure DevOps Services (dev.azure.com / *.visualstudio.com) and self-hosted
 * Azure DevOps Server instances. Listing requires the organization (and optionally project) to be
 * stored on the credential metadata.
 */
@Component
public class AzureRepoProvider implements RepoProvider {

    private final AzureDevOpsApiService azureDevOpsApiService;

    public AzureRepoProvider(AzureDevOpsApiService azureDevOpsApiService) {
        this.azureDevOpsApiService = azureDevOpsApiService;
    }

    @Override
    public RepositoryType supportedType() {
        return RepositoryType.AZURE;
    }

    @Override
    public String readFile(Repository repo, GitAuthMaterial auth, String path, String branch) {
        String token = auth.maybeToken()
                .orElseThrow(() -> new IllegalStateException(
                        "Azure DevOps file read requires a token, but no token was resolved"));
        AzureDevOpsApiService.Coordinates coords = AzureDevOpsApiService.parse(repo.getUrl());
        return azureDevOpsApiService.readFile(coords, path, branch, token);
    }

    @Override
    public List<RemoteRepositoryDto> listRepositories(RepoCredential credential, GitAuthMaterial auth) {
        String token = auth.maybeToken()
                .orElseThrow(() -> new IllegalStateException(
                        "Azure DevOps repository listing requires a token, but no token was resolved"));
        String apiBaseUrl = credential
                .getMetadata()
                .getAzureBaseUrl()
                .orElseThrow(() -> new IllegalStateException(
                        "Azure DevOps repository listing requires an organization on the credential metadata"));
        String project = credential.getMetadata().getAzureProject().orElse(null);
        return azureDevOpsApiService.listRepositories(apiBaseUrl, project, token);
    }

    @Override
    public PullRequestResultDto createPullRequest(
            Repository repo, GitAuthMaterial auth, CreatePullRequestCommand command) {
        String token = auth.maybeToken()
                .orElseThrow(() -> new IllegalStateException(
                        "Azure DevOps PR creation requires a token, but no token was resolved"));
        AzureDevOpsApiService.Coordinates coords = AzureDevOpsApiService.parse(repo.getUrl());
        return azureDevOpsApiService.createPullRequest(coords, command, token);
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
                        "Azure DevOps repository creation requires a token, but no token was resolved"));
        String apiBaseUrl = credential
                .getMetadata()
                .getAzureBaseUrl()
                .orElseThrow(() -> new IllegalStateException(
                        "Azure DevOps repository creation requires an organization on the credential metadata"));
        String project = credential
                .getMetadata()
                .getAzureProject()
                .orElseThrow(() -> new IllegalStateException(
                        "Azure DevOps repository creation requires a project on the credential metadata"));

        Optional<RemoteRepositoryDto> existing =
                azureDevOpsApiService.findRepository(apiBaseUrl, project, command.name(), token);
        if (existing.isPresent()) {
            if (azureDevOpsApiService.repositoryHasCommits(apiBaseUrl, project, command.name(), token)) {
                throw new RemoteRepositoryExistsException("Azure DevOps repository " + project + "/" + command.name()
                        + " already exists and is not empty");
            }
            return existing.get();
        }
        return azureDevOpsApiService.createRepository(apiBaseUrl, project, command, token);
    }
}
