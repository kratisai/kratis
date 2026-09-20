package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.GitLabApiClient;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriBuilderFactory;

@ExtendWith(MockitoExtension.class)
class GitLabApiServiceTest {

    @Mock
    private GitLabApiClient gitLabApiClient;

    private GitLabApiService service;

    @BeforeEach
    void setUp() {
        service = new GitLabApiService(gitLabApiClient);
    }

    @Test
    void listAvailableRepositories_parsesProjects() {
        String json =
                "[{\"path_with_namespace\": \"group/sub\", \"http_url_to_repo\": \"https://gitlab.com/group/sub.git\","
                        + "\"ssh_url_to_repo\": \"git@gitlab.com:group/sub.git\", \"default_branch\": \"main\"}]";
        when(gitLabApiClient.getProjects(any(UriBuilderFactory.class), eq(true), eq(true), eq(100), eq("tok")))
                .thenReturn(ResponseEntity.ok(json));

        List<RemoteRepositoryDto> result = service.listAvailableRepositories("tok", "https://gitlab.com");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("group/sub");
        assertThat(result.getFirst().cloneUrl()).isEqualTo("https://gitlab.com/group/sub.git");
        assertThat(result.getFirst().sshUrl()).isEqualTo("git@gitlab.com:group/sub.git");
        assertThat(result.getFirst().defaultBranch()).isEqualTo("main");
    }

    @Test
    void listAvailableRepositories_defaultsBranchToMain_whenMissing() {
        String json = "[{\"path_with_namespace\": \"group/repo\", \"http_url_to_repo\": \"x\", "
                + "\"ssh_url_to_repo\": \"y\"}]";
        when(gitLabApiClient.getProjects(any(UriBuilderFactory.class), eq(true), eq(true), eq(100), eq("tok")))
                .thenReturn(ResponseEntity.ok(json));

        List<RemoteRepositoryDto> result = service.listAvailableRepositories("tok", "https://gitlab.com");

        assertThat(result.getFirst().defaultBranch()).isEqualTo("main");
    }

    @Test
    void listAvailableRepositories_emptyToken_returnsEmptyList() {
        assertThat(service.listAvailableRepositories("", "https://gitlab.com")).isEmpty();
        assertThat(service.listAvailableRepositories(null, "https://gitlab.com"))
                .isEmpty();
    }

    @Test
    void listAvailableRepositories_apiError_throws() {
        when(gitLabApiClient.getProjects(any(UriBuilderFactory.class), eq(true), eq(true), eq(100), eq("tok")))
                .thenReturn(ResponseEntity.internalServerError().build());

        assertThatThrownBy(() -> service.listAvailableRepositories("tok", "https://gitlab.com"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("GitLab");
    }

    @Test
    void readFile_buildsEncodedUriAndReturnsBody() {
        when(gitLabApiClient.getRawFile(
                        any(UriBuilderFactory.class),
                        eq("group/my repo"),
                        eq("src/My File.java"),
                        eq("feature/x"),
                        eq("tok")))
                .thenReturn(ResponseEntity.ok("raw file body"));

        String result = service.readFile("group/my repo", "src/My File.java", "feature/x", "tok", "https://gitlab.com");

        assertThat(result).isEqualTo("raw file body");
    }

    @Test
    void readFile_emptyToken_throws() {
        assertThatThrownBy(() -> service.readFile("group/repo", "a.txt", "main", "", "https://gitlab.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void readFile_emptyResponse_throws() {
        when(gitLabApiClient.getRawFile(any(UriBuilderFactory.class), any(), any(), any(), eq("tok")))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> service.readFile("group/repo", "a.txt", "main", "tok", "https://gitlab.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void readFile_usesHeadRef_whenBranchBlank() {
        org.mockito.ArgumentCaptor<String> refCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(gitLabApiClient.getRawFile(any(UriBuilderFactory.class), any(), any(), refCaptor.capture(), eq("tok")))
                .thenReturn(ResponseEntity.ok("content"));

        service.readFile("group/repo", "a.txt", "  ", "tok", "https://gitlab.com");

        assertThat(refCaptor.getValue()).isEqualTo("HEAD");
    }

    @Test
    void extractProjectPath_parsesHttpsAndSshUrls() {
        assertThat(GitLabApiService.extractProjectPath("https://gitlab.com/group/repo.git"))
                .isEqualTo("group/repo");
        assertThat(GitLabApiService.extractProjectPath("https://gitlab.com/group/sub/repo"))
                .isEqualTo("group/sub/repo");
        assertThat(GitLabApiService.extractProjectPath("git@gitlab.com:group/repo.git"))
                .isEqualTo("group/repo");
        assertThat(GitLabApiService.extractProjectPath("git@gitlab.mycompany.com:group/repo.git"))
                .isEqualTo("group/repo");
    }

    @Test
    void extractProjectPath_invalidUrl_throws() {
        assertThatThrownBy(() -> GitLabApiService.extractProjectPath("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to extract");
    }

    @Test
    void extractBaseUrl_parsesHttpsAndSshUrls() {
        assertThat(GitLabApiService.extractBaseUrl("https://gitlab.com/group/repo.git"))
                .isEqualTo("https://gitlab.com");
        assertThat(GitLabApiService.extractBaseUrl("git@gitlab.mycompany.com:group/repo.git"))
                .isEqualTo("https://gitlab.mycompany.com");
    }

    @Test
    void extractBaseUrl_invalidUrl_throws() {
        assertThatThrownBy(() -> GitLabApiService.extractBaseUrl("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to extract");
    }

    @Test
    void getAuthenticatedUsername_returnsUsername() {
        when(gitLabApiClient.getUser(any(UriBuilderFactory.class), eq("tok")))
                .thenReturn(ResponseEntity.ok("{\"username\": \"fake-user\"}"));

        assertThat(service.getAuthenticatedUsername("tok", "https://gitlab.com"))
                .isEqualTo("fake-user");
    }

    @Test
    void getAuthenticatedUsername_missingUsername_throws() {
        when(gitLabApiClient.getUser(any(UriBuilderFactory.class), eq("tok"))).thenReturn(ResponseEntity.ok("{}"));

        assertThatThrownBy(() -> service.getAuthenticatedUsername("tok", "https://gitlab.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
    }

    @Test
    void getGroupId_returnsId() {
        when(gitLabApiClient.getGroup(any(UriBuilderFactory.class), eq("my-group"), eq("tok")))
                .thenReturn(ResponseEntity.ok("{\"id\": 77}"));

        assertThat(service.getGroupId("my-group", "tok", "https://gitlab.com")).isEqualTo(77L);
    }

    @Test
    void getGroupId_missingId_throws() {
        when(gitLabApiClient.getGroup(any(UriBuilderFactory.class), eq("my-group"), eq("tok")))
                .thenReturn(ResponseEntity.ok("{}"));

        assertThatThrownBy(() -> service.getGroupId("my-group", "tok", "https://gitlab.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void findProject_returnsEmptyOn404() {
        when(gitLabApiClient.getProject(any(UriBuilderFactory.class), eq("fake-user/repo"), eq("tok")))
                .thenReturn(ResponseEntity.status(404).body("{}"));

        assertThat(service.findProject("fake-user/repo", "tok", "https://gitlab.com"))
                .isEmpty();
    }

    @Test
    void findProject_thrownNotFound_returnsEmpty() {
        when(gitLabApiClient.getProject(any(UriBuilderFactory.class), eq("fake-user/repo"), eq("tok")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        assertThat(service.findProject("fake-user/repo", "tok", "https://gitlab.com"))
                .isEmpty();
    }

    @Test
    void findProject_thrownForbidden_propagates() {
        when(gitLabApiClient.getProject(any(UriBuilderFactory.class), eq("fake-user/repo"), eq("tok")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.findProject("fake-user/repo", "tok", "https://gitlab.com"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void findProject_returnsProject() {
        when(gitLabApiClient.getProject(any(UriBuilderFactory.class), eq("fake-user/repo"), eq("tok")))
                .thenReturn(
                        ResponseEntity.ok(
                                "{\"path_with_namespace\": \"fake-user/repo\", \"http_url_to_repo\": \"https://gitlab.com/fake-user/repo.git\", \"default_branch\": \"main\"}"));

        RemoteRepositoryDto result = service.findProject("fake-user/repo", "tok", "https://gitlab.com")
                .orElseThrow();

        assertThat(result.name()).isEqualTo("fake-user/repo");
        assertThat(result.cloneUrl()).isEqualTo("https://gitlab.com/fake-user/repo.git");
    }

    @Test
    void projectHasCommits_reflectsCommitList() {
        when(gitLabApiClient.getCommits(any(UriBuilderFactory.class), eq("fake-user/repo"), eq(1), eq("tok")))
                .thenReturn(ResponseEntity.ok("[{\"id\": \"abc\"}]"))
                .thenReturn(ResponseEntity.ok("[]"));

        assertThat(service.projectHasCommits("fake-user/repo", "tok", "https://gitlab.com"))
                .isTrue();
        assertThat(service.projectHasCommits("fake-user/repo", "tok", "https://gitlab.com"))
                .isFalse();
    }

    @Test
    void createRepository_postsProjectAndParsesResult() {
        when(gitLabApiClient.createProject(any(UriBuilderFactory.class), any(), eq("tok")))
                .thenReturn(
                        ResponseEntity.status(201)
                                .body(
                                        "{\"path_with_namespace\": \"fake-user/repo\", \"http_url_to_repo\": \"https://gitlab.com/fake-user/repo.git\", \"default_branch\": \"main\"}"));

        RemoteRepositoryDto result = service.createRepository(
                null,
                new CreateRepositoryCommand("repo", RepositoryVisibility.INTERNAL, "main"),
                "tok",
                "https://gitlab.com");

        assertThat(result.name()).isEqualTo("fake-user/repo");
    }

    @Test
    void createRepository_errorStatus_throws() {
        when(gitLabApiClient.createProject(any(UriBuilderFactory.class), any(), eq("tok")))
                .thenReturn(ResponseEntity.status(400).body("{}"));

        CreateRepositoryCommand command = new CreateRepositoryCommand("repo", RepositoryVisibility.PRIVATE, "main");
        assertThatThrownBy(() -> service.createRepository(null, command, "tok", "https://gitlab.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creation failed");
    }
}
