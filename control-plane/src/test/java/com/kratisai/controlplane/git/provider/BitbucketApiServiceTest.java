package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.BitbucketApiClient;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class BitbucketApiServiceTest {

    @Mock
    private BitbucketApiClient bitbucketApiClient;

    private BitbucketApiService service;

    @BeforeEach
    void setUp() {
        service = new BitbucketApiService(bitbucketApiClient);
    }

    @Test
    void listRepositories_parsesValuesWithCloneLinks() {
        String json = "{\"values\": [{\"full_name\": \"team/repo\", \"links\": {\"clone\": ["
                + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/team/repo.git\"},"
                + "{\"name\": \"ssh\", \"href\": \"git@bitbucket.org:team/repo.git\"}]},"
                + "\"mainbranch\": {\"name\": \"master\"}}]}";
        when(bitbucketApiClient.getRepositories(any(), anyInt(), anyInt(), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok(json));

        List<RemoteRepositoryDto> result = service.listRepositories(null, "tok");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("team/repo");
        assertThat(result.getFirst().cloneUrl()).isEqualTo("https://bitbucket.org/team/repo.git");
        assertThat(result.getFirst().sshUrl()).isEqualTo("git@bitbucket.org:team/repo.git");
        assertThat(result.getFirst().defaultBranch()).isEqualTo("master");
    }

    @Test
    void listRepositories_followsNextPagination() {
        String page1 =
                "{\"values\": [{\"full_name\": \"team/one\"}], \"next\": \"https://api.bitbucket.org/2.0/repositories?page=2\"}";
        String page2 = "{\"values\": [{\"full_name\": \"team/two\"}]}";
        when(bitbucketApiClient.getRepositories(any(), anyInt(), anyInt(), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok(page1))
                .thenReturn(ResponseEntity.ok(page2));

        List<RemoteRepositoryDto> result = service.listRepositories(null, "tok");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RemoteRepositoryDto::name).containsExactly("team/one", "team/two");
    }

    @Test
    void listRepositories_withWorkspace_scopesRequestToWorkspace() {
        String json = "{\"values\": [{\"full_name\": \"myteam/repo\"}]}";
        when(bitbucketApiClient.getWorkspaceRepositories(eq("myteam"), eq("member"), eq(100), eq(1), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok(json));

        service.listRepositories("myteam", "tok");

        org.mockito.Mockito.verify(bitbucketApiClient)
                .getWorkspaceRepositories(eq("myteam"), eq("member"), eq(100), eq(1), eq("Bearer tok"));
    }

    @Test
    void listRepositories_emptyToken_throws() {
        assertThatThrownBy(() -> service.listRepositories(null, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
        assertThatThrownBy(() -> service.listRepositories(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void listRepositories_apiError_throws() {
        when(bitbucketApiClient.getRepositories(any(), anyInt(), anyInt(), eq("Bearer tok")))
                .thenReturn(ResponseEntity.internalServerError().build());

        assertThatThrownBy(() -> service.listRepositories(null, "tok"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Bitbucket");
    }

    @Test
    void readFile_returnsRawBody() {
        when(bitbucketApiClient.getSource(eq("team"), eq("repo"), eq("main"), eq("src/a.txt"), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok("raw content"));

        String result = service.readFile("team", "repo", "src/a.txt", "main", "tok");

        assertThat(result).isEqualTo("raw content");
    }

    @Test
    void readFile_defaultsRefToMain_whenBranchBlank() {
        when(bitbucketApiClient.getSource(eq("team"), eq("repo"), eq("main"), eq("src/a.txt"), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok("raw content"));

        String result = service.readFile("team", "repo", "src/a.txt", "  ", "tok");

        assertThat(result).isEqualTo("raw content");
    }

    @Test
    void readFile_emptyToken_throws() {
        assertThatThrownBy(() -> service.readFile("team", "repo", "a.txt", "main", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void readFile_emptyResponse_throws() {
        when(bitbucketApiClient.getSource(eq("team"), eq("repo"), eq("main"), eq("a.txt"), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> service.readFile("team", "repo", "a.txt", "main", "tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void extractWorkspace_parsesHttpsAndSshUrls() {
        assertThat(BitbucketApiService.extractWorkspace("https://bitbucket.org/team/repo"))
                .isEqualTo("team");
        assertThat(BitbucketApiService.extractWorkspace("git@bitbucket.org:team/repo.git"))
                .isEqualTo("team");
    }

    @Test
    void extractRepoSlug_parsesHttpsAndSshUrls() {
        assertThat(BitbucketApiService.extractRepoSlug("https://bitbucket.org/team/repo"))
                .isEqualTo("repo");
        assertThat(BitbucketApiService.extractRepoSlug("git@bitbucket.org:team/repo.git"))
                .isEqualTo("repo");
    }

    @Test
    void extract_invalidUrl_throws() {
        assertThatThrownBy(() -> BitbucketApiService.extractWorkspace("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to extract");
        assertThatThrownBy(() -> BitbucketApiService.extractRepoSlug("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to extract");
    }

    @Test
    void findRepository_returnsEmptyOn404() {
        when(bitbucketApiClient.getRepository(eq("myteam"), eq("repo"), eq("Bearer tok")))
                .thenReturn(ResponseEntity.status(404).body("{}"));

        assertThat(service.findRepository("myteam", "repo", "tok")).isEmpty();
    }

    @Test
    void findRepository_returnsRepository() {
        when(bitbucketApiClient.getRepository(eq("myteam"), eq("repo"), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok("{\"full_name\": \"myteam/repo\", \"links\": {\"clone\": ["
                        + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/myteam/repo.git\"}]},"
                        + "\"mainbranch\": {\"name\": \"main\"}}"));

        RemoteRepositoryDto result =
                service.findRepository("myteam", "repo", "tok").orElseThrow();

        assertThat(result.name()).isEqualTo("myteam/repo");
        assertThat(result.cloneUrl()).isEqualTo("https://bitbucket.org/myteam/repo.git");
    }

    @Test
    void repositoryHasCommits_reflectsBranchList() {
        when(bitbucketApiClient.getBranches(eq("myteam"), eq("repo"), eq(1), eq("Bearer tok")))
                .thenReturn(ResponseEntity.ok("{\"values\": [{\"name\": \"main\"}]}"))
                .thenReturn(ResponseEntity.ok("{\"values\": []}"));

        assertThat(service.repositoryHasCommits("myteam", "repo", "tok")).isTrue();
        assertThat(service.repositoryHasCommits("myteam", "repo", "tok")).isFalse();
    }

    @Test
    void createRepository_postsAndParsesResult() {
        when(bitbucketApiClient.createRepository(eq("myteam"), eq("repo"), any(), eq("Bearer tok")))
                .thenReturn(ResponseEntity.status(201)
                        .body("{\"full_name\": \"myteam/repo\", \"links\": {\"clone\": ["
                                + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/myteam/repo.git\"}]},"
                                + "\"mainbranch\": {\"name\": \"main\"}}"));

        RemoteRepositoryDto result = service.createRepository(
                "myteam", new CreateRepositoryCommand("repo", RepositoryVisibility.PRIVATE, "main"), "tok");

        assertThat(result.cloneUrl()).isEqualTo("https://bitbucket.org/myteam/repo.git");
    }

    @Test
    void createRepository_errorStatus_throws() {
        when(bitbucketApiClient.createRepository(eq("myteam"), eq("repo"), any(), eq("Bearer tok")))
                .thenReturn(ResponseEntity.status(400).body("{}"));

        CreateRepositoryCommand command = new CreateRepositoryCommand("repo", RepositoryVisibility.PRIVATE, "main");
        assertThatThrownBy(() -> service.createRepository("myteam", command, "tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creation failed");
    }
}
