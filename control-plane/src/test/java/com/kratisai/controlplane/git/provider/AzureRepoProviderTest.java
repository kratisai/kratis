package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.RepositoryVisibility;
import com.kratisai.controlplane.model.Team;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AzureRepoProviderTest {

    @Mock
    private AzureDevOpsApiService azureDevOpsApiService;

    private AzureRepoProvider provider;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        provider = new AzureRepoProvider(azureDevOpsApiService);
        testRepo = new Repository(
                "test-repo", "https://dev.azure.com/myorg/myproject/_git/azure-repo", "main", RepositoryType.AZURE);
    }

    @Test
    void supportedType_returnsAzure() {
        assertThat(provider.supportedType()).isEqualTo(RepositoryType.AZURE);
    }

    @Test
    void readFile_delegatesToServiceWithParsedCoordinates() {
        AzureDevOpsApiService.Coordinates coords = new AzureDevOpsApiService.Coordinates(
                "https://dev.azure.com/myorg", "myorg", "myproject", "azure-repo");
        when(azureDevOpsApiService.readFile(any(), eq("src/App.java"), eq("main"), eq("tok")))
                .thenReturn("file content");

        String result = provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "src/App.java", "main");

        assertThat(result).isEqualTo("file content");
        verify(azureDevOpsApiService).readFile(eq(coords), eq("src/App.java"), eq("main"), eq("tok"));
    }

    @Test
    void readFile_withoutToken_throws() {
        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.none(), "src/App.java", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void readFile_withInvalidUrl_throws() {
        testRepo.setUrl("invalid-url");

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "src/App.java", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse");
    }

    @Test
    void listRepositories_usesBaseUrlAndProjectFromMetadata() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "azure-pat", null, null);
        credential.setProviderMetadata("{\"azureBaseUrl\":\"https://dev.azure.com/myorg\",\"azureProject\":\"proj\"}");
        RemoteRepositoryDto remote =
                new RemoteRepositoryDto("proj/repo", "https://dev.azure.com/myorg/proj/_git/repo", "", "main");
        when(azureDevOpsApiService.listRepositories(eq("https://dev.azure.com/myorg"), eq("proj"), eq("tok")))
                .thenReturn(List.of(remote));

        List<RemoteRepositoryDto> result = provider.listRepositories(credential, GitAuthMaterial.ofToken("tok"));

        assertThat(result).containsExactly(remote);
    }

    @Test
    void listRepositories_withoutBaseUrlMetadata_throws() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "azure-pat", null, null);
        credential.setProviderMetadata("{\"provider\":\"azure\"}");

        assertThatThrownBy(() -> provider.listRepositories(credential, GitAuthMaterial.ofToken("tok")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("organization");
    }

    @Test
    void listRepositories_withoutToken_throws() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "azure-pat", null, null);
        credential.setProviderMetadata("{\"azureBaseUrl\":\"https://dev.azure.com/myorg\"}");

        assertThatThrownBy(() -> provider.listRepositories(credential, GitAuthMaterial.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void createPullRequest_withValidToken_delegatesToApiService() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("feature/azure", "main", "Azure PR Title", "Azure Description", false);
        PullRequestResultDto expected = new PullRequestResultDto(
                99, "https://dev.azure.com/myorg/myproject/_git/azure-repo/pullrequest/99", "feature/azure", "main");

        AzureDevOpsApiService.Coordinates coords = new AzureDevOpsApiService.Coordinates(
                "https://dev.azure.com/myorg", "myorg", "myproject", "azure-repo");

        when(azureDevOpsApiService.createPullRequest(eq(coords), eq(command), eq("tok")))
                .thenReturn(expected);

        PullRequestResultDto result = provider.createPullRequest(testRepo, GitAuthMaterial.ofToken("tok"), command);

        assertThat(result).isEqualTo(expected);
        assertThat(provider.supportsPullRequests()).isTrue();
    }

    @Test
    void createPullRequest_withoutToken_throws() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("feature/azure", "main", "Azure PR Title", "Azure Description", false);

        assertThatThrownBy(() -> provider.createPullRequest(testRepo, GitAuthMaterial.none(), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void createRepository_withOrgAndProject_delegatesToApiService() {
        RepoCredential credential = new RepoCredential(new Team("t", "d"), "az", null, null);
        credential.setProviderMetadata(
                "{\"provider\":\"azure\",\"azureBaseUrl\":\"https://dev.azure.com/myorg\",\"azureProject\":\"myproject\"}");
        CreateRepositoryCommand command =
                new CreateRepositoryCommand("fresh-repo", RepositoryVisibility.PRIVATE, "main");
        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "myproject/fresh-repo", "https://dev.azure.com/myorg/myproject/_git/fresh-repo", "", "main");

        when(azureDevOpsApiService.findRepository("https://dev.azure.com/myorg", "myproject", "fresh-repo", "tok"))
                .thenReturn(Optional.empty());
        when(azureDevOpsApiService.createRepository("https://dev.azure.com/myorg", "myproject", command, "tok"))
                .thenReturn(remote);

        RemoteRepositoryDto result = provider.createRepository(credential, GitAuthMaterial.ofToken("tok"), command);

        assertThat(result).isEqualTo(remote);
    }

    @Test
    void createRepository_withoutProject_throws() {
        RepoCredential credential = new RepoCredential(new Team("t", "d"), "az", null, null);
        credential.setProviderMetadata("{\"provider\":\"azure\",\"azureBaseUrl\":\"https://dev.azure.com/myorg\"}");
        CreateRepositoryCommand command =
                new CreateRepositoryCommand("fresh-repo", RepositoryVisibility.PRIVATE, "main");

        assertThatThrownBy(() -> provider.createRepository(credential, GitAuthMaterial.ofToken("tok"), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project");
    }
}
