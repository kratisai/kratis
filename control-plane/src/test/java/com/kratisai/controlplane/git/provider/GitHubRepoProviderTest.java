package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class GitHubRepoProviderTest {

    @Mock
    private GitHubApiService gitHubApiService;

    private GitHubRepoProvider provider;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        provider = new GitHubRepoProvider(gitHubApiService);
        testRepo = new Repository(
                "test-repo", "https://github.com/test-owner/test-repo.git", "main", RepositoryType.GITHUB);
    }

    @Test
    void supportedType_returnsGithub() {
        assertThat(provider.supportedType()).isEqualTo(RepositoryType.GITHUB);
    }

    @Test
    void readFile_withBase64Content_decodesSuccessfully() {
        String fileContent = "public class Test {}";
        when(gitHubApiService.readFile(eq("test-owner"), eq("test-repo"), eq("src/Test.java"), eq("main"), eq("tok")))
                .thenReturn(fileContent);

        String result = provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "src/Test.java", "main");

        assertThat(result).isEqualTo(fileContent);
    }

    @Test
    void readFile_withFileTooLarge_throws() {
        when(gitHubApiService.readFile(eq("test-owner"), eq("test-repo"), eq("large.txt"), eq("main"), eq("tok")))
                .thenThrow(new IllegalStateException("File too large (2000000 bytes). Maximum is 1000000"));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "large.txt", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void readFile_withNonBase64Encoding_throws() {
        when(gitHubApiService.readFile(eq("test-owner"), eq("test-repo"), eq("file.txt"), eq("main"), eq("tok")))
                .thenThrow(new IllegalStateException(
                        "Unexpected GitHub Contents API response for file.txt: encoding=utf-8"));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "file.txt", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encoding");
    }

    @Test
    void readFile_withNullContent_throws() {
        when(gitHubApiService.readFile(eq("test-owner"), eq("test-repo"), eq("file.txt"), eq("main"), eq("tok")))
                .thenThrow(new IllegalStateException(
                        "Unexpected GitHub Contents API response for file.txt: encoding=base64"));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "file.txt", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encoding");
    }

    @Test
    void readFile_withHttpNotFound_propagates() {
        when(gitHubApiService.readFile(eq("test-owner"), eq("test-repo"), eq("missing.txt"), eq("main"), eq("tok")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "missing.txt", "main"))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void readFile_withoutToken_throws() {
        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.none(), "file.txt", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void readFile_withInvalidUrl_throws() {
        testRepo.setUrl("invalid-url");

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "file.txt", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to extract");
    }

    @Test
    void readFile_withSshStyleUrl_extractsOwnerAndRepo() {
        testRepo.setUrl("git@github.com:ssh-owner/ssh-repo.git");
        when(gitHubApiService.readFile(eq("ssh-owner"), eq("ssh-repo"), eq("f.txt"), eq("main"), eq("tok")))
                .thenReturn("content");

        String result = provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "f.txt", "main");

        assertThat(result).isEqualTo("content");
    }

    @Test
    void listRepositories_withGitHubAppInstallation_usesInstallationListing() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "app", null, null);
        credential.setProviderMetadata("{\"installationId\":\"123\",\"provider\":\"github\"}");
        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "org/repo", "https://github.com/org/repo.git", "git@github.com:org/repo.git", "main");
        when(gitHubApiService.listAvailableRepositories("123")).thenReturn(List.of(remote));

        List<RemoteRepositoryDto> result = provider.listRepositories(credential, GitAuthMaterial.none());

        assertThat(result).containsExactly(remote);
    }

    @Test
    void listRepositories_withPatToken_usesUserListing() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "pat", null, null);
        credential.setProviderMetadata("{\"provider\":\"github\"}");
        RemoteRepositoryDto remote = new RemoteRepositoryDto("me/repo", "https://github.com/me/repo.git", "", "main");
        when(gitHubApiService.listUserRepositories("ghp_token")).thenReturn(List.of(remote));

        List<RemoteRepositoryDto> result = provider.listRepositories(credential, GitAuthMaterial.ofToken("ghp_token"));

        assertThat(result).containsExactly(remote);
    }

    @Test
    void listRepositories_withoutInstallationOrToken_throws() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "empty", null, null);
        credential.setProviderMetadata("{\"provider\":\"github\"}");

        assertThatThrownBy(() -> provider.listRepositories(credential, GitAuthMaterial.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installationId");
    }

    @Test
    void createPullRequest_withValidToken_delegatesToApiService() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("kratis/test-branch", "main", "Add feature", "Feature description", false);
        PullRequestResultDto expected = new PullRequestResultDto(
                42, "https://github.com/test-owner/test-repo/pull/42", "kratis/test-branch", "main");

        when(gitHubApiService.createPullRequest("test-owner", "test-repo", command, "ghp_tok"))
                .thenReturn(expected);

        PullRequestResultDto result = provider.createPullRequest(testRepo, GitAuthMaterial.ofToken("ghp_tok"), command);

        assertThat(result).isEqualTo(expected);
        assertThat(provider.supportsPullRequests()).isTrue();
    }

    @Test
    void createPullRequest_withoutToken_throws() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("kratis/test-branch", "main", "Add feature", "Feature description", false);

        assertThatThrownBy(() -> provider.createPullRequest(testRepo, GitAuthMaterial.none(), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a token");
    }

    @Test
    void supportsRepositoryCreation_returnsTrue() {
        assertThat(provider.supportsRepositoryCreation()).isTrue();
    }

    @Test
    void createRepository_pat_createsUnderAuthenticatedUser() {
        RepoCredential credential = new RepoCredential(new Team("t", "d"), "pat", null, null);
        credential.setProviderMetadata("{\"provider\":\"github\"}");
        CreateRepositoryCommand command =
                new CreateRepositoryCommand("fresh-repo", RepositoryVisibility.PRIVATE, "main");
        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "fake-user/fresh-repo", "https://github.com/fake-user/fresh-repo.git", "", "main");

        when(gitHubApiService.getAuthenticatedAccount("tok"))
                .thenReturn(new GitHubApiService.GitHubAccount("fake-user", false));
        when(gitHubApiService.findRepository("fake-user", "fresh-repo", "tok")).thenReturn(Optional.empty());
        when(gitHubApiService.createRepository("fake-user", false, command, "tok"))
                .thenReturn(remote);

        RemoteRepositoryDto result = provider.createRepository(credential, GitAuthMaterial.ofToken("tok"), command);

        assertThat(result).isEqualTo(remote);
    }

    @Test
    void createRepository_existingNonEmpty_throws() {
        RepoCredential credential = new RepoCredential(new Team("t", "d"), "pat", null, null);
        credential.setProviderMetadata("{\"provider\":\"github\"}");
        CreateRepositoryCommand command =
                new CreateRepositoryCommand("fresh-repo", RepositoryVisibility.PRIVATE, "main");
        RemoteRepositoryDto remote = new RemoteRepositoryDto(
                "fake-user/fresh-repo", "https://github.com/fake-user/fresh-repo.git", "", "main");

        when(gitHubApiService.getAuthenticatedAccount("tok"))
                .thenReturn(new GitHubApiService.GitHubAccount("fake-user", false));
        when(gitHubApiService.findRepository("fake-user", "fresh-repo", "tok")).thenReturn(Optional.of(remote));
        when(gitHubApiService.repositoryHasCommits("fake-user", "fresh-repo", "tok"))
                .thenReturn(true);

        assertThatThrownBy(() -> provider.createRepository(credential, GitAuthMaterial.ofToken("tok"), command))
                .isInstanceOf(RemoteRepositoryExistsException.class)
                .hasMessageContaining("already exists and is not empty");
    }

    @Test
    void createRepository_appOnPersonalAccount_throws() {
        RepoCredential credential = new RepoCredential(new Team("t", "d"), "app", null, null);
        credential.setProviderMetadata("{\"installationId\":\"123\",\"provider\":\"github\"}");
        CreateRepositoryCommand command =
                new CreateRepositoryCommand("fresh-repo", RepositoryVisibility.PRIVATE, "main");

        when(gitHubApiService.getInstallationAccount("123"))
                .thenReturn(new GitHubApiService.GitHubAccount("personal", false));

        assertThatThrownBy(() -> provider.createRepository(credential, GitAuthMaterial.ofToken("tok"), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("personal accounts");
    }
}
