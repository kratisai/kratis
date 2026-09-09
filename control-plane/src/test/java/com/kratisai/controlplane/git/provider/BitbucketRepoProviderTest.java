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
class BitbucketRepoProviderTest {

    @Mock
    private BitbucketApiService bitbucketApiService;

    private BitbucketRepoProvider provider;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        provider = new BitbucketRepoProvider(bitbucketApiService);
        testRepo = new Repository(
                "test-repo", "https://bitbucket.org/myteam/bitbucket-repo", "main", RepositoryType.BITBUCKET);
    }

    @Test
    void supportedType_returnsBitbucket() {
        assertThat(provider.supportedType()).isEqualTo(RepositoryType.BITBUCKET);
    }

    @Test
    void readFile_delegatesToService() {
        when(bitbucketApiService.readFile(
                        eq("myteam"), eq("bitbucket-repo"), eq("src/App.java"), eq("main"), eq("tok")))
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
    void readFile_withSshStyleUrl_extractsWorkspaceAndRepo() {
        testRepo.setUrl("git@bitbucket.org:ssh-team/ssh-repo.git");
        when(bitbucketApiService.readFile(eq("ssh-team"), eq("ssh-repo"), eq("f.txt"), eq("main"), eq("tok")))
                .thenReturn("content");

        String result = provider.readFile(testRepo, GitAuthMaterial.ofToken("tok"), "f.txt", "main");

        assertThat(result).isEqualTo("content");
    }

    @Test
    void listRepositories_delegatesToService() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "bitbucket-pat", null, null);
        credential.setProviderMetadata("{\"provider\":\"bitbucket\"}");
        RemoteRepositoryDto remote =
                new RemoteRepositoryDto("team/repo", "https://bitbucket.org/team/repo.git", "", "main");
        when(bitbucketApiService.listRepositories(null, "tok")).thenReturn(List.of(remote));

        List<RemoteRepositoryDto> result = provider.listRepositories(credential, GitAuthMaterial.ofToken("tok"));

        assertThat(result).containsExactly(remote);
    }

    @Test
    void listRepositories_usesWorkspaceFromMetadata_whenPresent() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "bitbucket-pat", null, null);
        credential.setProviderMetadata("{\"provider\":\"bitbucket\",\"bitbucketWorkspace\":\"myteam\"}");
        when(bitbucketApiService.listRepositories("myteam", "tok")).thenReturn(List.of());

        provider.listRepositories(credential, GitAuthMaterial.ofToken("tok"));

        org.mockito.Mockito.verify(bitbucketApiService).listRepositories("myteam", "tok");
    }

    @Test
    void listRepositories_withoutToken_throws() {
        Team team = new Team("t", "d");
        RepoCredential credential = new RepoCredential(team, "bitbucket-pat", null, null);

        assertThatThrownBy(() -> provider.listRepositories(credential, GitAuthMaterial.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void createPullRequest_withValidToken_delegatesToApiService() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("feature/bb", "main", "BB PR Title", "BB Description", false);
        PullRequestResultDto expected = new PullRequestResultDto(
                5, "https://bitbucket.org/myteam/bitbucket-repo/pull-requests/5", "feature/bb", "main");

        when(bitbucketApiService.createPullRequest(eq("myteam"), eq("bitbucket-repo"), eq(command), eq("tok")))
                .thenReturn(expected);

        PullRequestResultDto result = provider.createPullRequest(testRepo, GitAuthMaterial.ofToken("tok"), command);

        assertThat(result).isEqualTo(expected);
        assertThat(provider.supportsPullRequests()).isTrue();
    }

    @Test
    void createPullRequest_withoutToken_throws() {
        CreatePullRequestCommand command =
                new CreatePullRequestCommand("feature/bb", "main", "BB PR Title", "BB Description", false);

        assertThatThrownBy(() -> provider.createPullRequest(testRepo, GitAuthMaterial.none(), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }
}
