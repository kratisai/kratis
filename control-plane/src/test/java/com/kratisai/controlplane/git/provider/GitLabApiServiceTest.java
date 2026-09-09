package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.GitLabApiClient;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

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
        when(gitLabApiClient.getRepositories(any(URI.class), eq("tok"))).thenReturn(ResponseEntity.ok(json));

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
        when(gitLabApiClient.getRepositories(any(URI.class), eq("tok"))).thenReturn(ResponseEntity.ok(json));

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
        when(gitLabApiClient.getRepositories(any(URI.class), eq("tok")))
                .thenReturn(ResponseEntity.internalServerError().build());

        assertThatThrownBy(() -> service.listAvailableRepositories("tok", "https://gitlab.com"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("GitLab");
    }

    @Test
    void readFile_buildsEncodedUriAndReturnsBody() {
        when(gitLabApiClient.getRawFile(any(URI.class), eq("tok"))).thenReturn(ResponseEntity.ok("raw file body"));

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
        when(gitLabApiClient.getRawFile(any(URI.class), eq("tok"))).thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> service.readFile("group/repo", "a.txt", "main", "tok", "https://gitlab.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void readFile_usesHeadRef_whenBranchBlank() {
        org.mockito.ArgumentCaptor<URI> captor = org.mockito.ArgumentCaptor.forClass(URI.class);
        when(gitLabApiClient.getRawFile(captor.capture(), eq("tok"))).thenReturn(ResponseEntity.ok("content"));

        service.readFile("group/repo", "a.txt", "  ", "tok", "https://gitlab.com");

        assertThat(captor.getValue().toString()).contains("ref=HEAD");
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
}
