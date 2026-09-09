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
import com.kratisai.controlplane.model.Team;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitLabRepoProviderTest {

    @Mock
    private GitLabApiService gitLabApiService;

    private GitLabRepoProvider provider;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        provider = new GitLabRepoProvider(gitLabApiService);
        testRepo = new Repository(
                "test-repo", "https://gitlab.com/group/subgroup/repo.git", "main", RepositoryType.GITLAB);
    }

    @Test
    void supportedType_returnsGitlab() {
        assertThat(provider.supportedType()).isEqualTo(RepositoryType.GITLAB);
    }

    @Test
    void readFile_delegatesToServiceWithProjectPathAndBaseUrl() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "gitlab-pat", null, null);
        credential.setProviderMetadata("{\"gitlabUrl\":\"https://gitlab.example.com\"}");
        testRepo.setCredential(credential);

        when(gitLabApiService.readFile(
                        eq("group/subgroup/repo"),
                        eq("src/App.java"),
                        eq("main"),
                        eq("tok"),
                        eq("https://gitlab.example.com")))
                .thenReturn("file content");

        String result = provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "src/App.java", "main");

        assertThat(result).isEqualTo("file content");
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
                .hasMessageContaining("Unable to extract");
    }

    @Test
    void listRepositories_usesGitLabUrlFromMetadata_whenPresent() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "gitlab-pat", null, null);
        credential.setProviderMetadata("{\"gitlabUrl\":\"https://gitlab.selfhosted.com\"}");
        RemoteRepositoryDto remote =
                new RemoteRepositoryDto("group/repo", "https://gitlab.selfhosted.com/group/repo.git", "", "main");
        when(gitLabApiService.listAvailableRepositories(eq("tok"), eq("https://gitlab.selfhosted.com")))
                .thenReturn(List.of(remote));

        List<RemoteRepositoryDto> result = provider.listRepositories(credential, GitAuthMaterial.ofToken("tok"));

        assertThat(result).containsExactly(remote);
    }

    @Test
    void listRepositories_defaultsToGitlabCom_whenNoMetadata() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "gitlab-pat", null, null);
        credential.setProviderMetadata("{}");
        RemoteRepositoryDto remote =
                new RemoteRepositoryDto("group/repo", "https://gitlab.com/group/repo.git", "", "main");
        when(gitLabApiService.listAvailableRepositories(eq("tok"), eq("https://gitlab.com")))
                .thenReturn(List.of(remote));

        List<RemoteRepositoryDto> result = provider.listRepositories(credential, GitAuthMaterial.ofToken("tok"));

        assertThat(result).containsExactly(remote);
    }

    @Test
    void listRepositories_withoutToken_throws() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "gitlab-pat", null, null);

        assertThatThrownBy(() -> provider.listRepositories(credential, GitAuthMaterial.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void createPullRequest_withValidToken_delegatesToApiService() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("feature/test", "main", "MR Title", "MR Description", true);
        PullRequestResultDto expected = new PullRequestResultDto(
                10, "https://gitlab.com/group/subgroup/repo/-/merge_requests/10", "feature/test", "main");

        when(gitLabApiService.createPullRequest(
                        eq("group/subgroup/repo"), eq(command), eq("tok"), eq("https://gitlab.com")))
                .thenReturn(expected);

        PullRequestResultDto result = provider.createPullRequest(testRepo, GitAuthMaterial.ofToken("tok"), command);

        assertThat(result).isEqualTo(expected);
        assertThat(provider.supportsPullRequests()).isTrue();
    }

    @Test
    void createPullRequest_withoutToken_throws() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("feature/test", "main", "MR Title", "MR Description", false);

        assertThatThrownBy(() -> provider.createPullRequest(testRepo, GitAuthMaterial.none(), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }
}
