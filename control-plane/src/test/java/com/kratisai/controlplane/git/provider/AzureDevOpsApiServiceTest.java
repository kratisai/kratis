package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.AzureDevOpsApiClient;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriBuilderFactory;

@ExtendWith(MockitoExtension.class)
class AzureDevOpsApiServiceTest {

    @Mock
    private AzureDevOpsApiClient azureDevOpsApiClient;

    private AzureDevOpsApiService service;

    @BeforeEach
    void setUp() {
        service = new AzureDevOpsApiService(azureDevOpsApiClient);
    }

    private static String basicAuthHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(":tok".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listRepositories_parsesValueArray() {
        String json = "{\"value\": [{\"name\": \"azure-core\", \"id\": \"repo-1\","
                + "\"remoteUrl\": \"https://dev.azure.com/myorg/proj/_git/azure-core\","
                + "\"sshUrl\": \"git@ssh.dev.azure.com:v3/myorg/proj/azure-core\","
                + "\"defaultBranch\": \"refs/heads/main\","
                + "\"project\": {\"name\": \"proj\"}}]}";
        when(azureDevOpsApiClient.getProjectRepositories(
                        any(UriBuilderFactory.class), eq("proj"), eq("7.1"), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.ok(json));

        List<RemoteRepositoryDto> result = service.listRepositories("https://dev.azure.com/myorg", "proj", "tok");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("proj/azure-core");
        assertThat(result.getFirst().cloneUrl()).isEqualTo("https://dev.azure.com/myorg/proj/_git/azure-core");
        assertThat(result.getFirst().sshUrl()).isEqualTo("git@ssh.dev.azure.com:v3/myorg/proj/azure-core");
        assertThat(result.getFirst().defaultBranch()).isEqualTo("main");
    }

    @Test
    void listRepositories_withoutProject_listsWholeOrg() {
        String json = "{\"value\": [{\"name\": \"core\"}]}";
        when(azureDevOpsApiClient.getOrganizationRepositories(
                        any(UriBuilderFactory.class), eq("7.1"), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.ok(json));

        service.listRepositories("https://dev.azure.com/myorg", null, "tok");

        org.mockito.Mockito.verify(azureDevOpsApiClient)
                .getOrganizationRepositories(any(UriBuilderFactory.class), eq("7.1"), eq(basicAuthHeader()));
        org.mockito.Mockito.verify(azureDevOpsApiClient, org.mockito.Mockito.never())
                .getProjectRepositories(any(UriBuilderFactory.class), any(), any(), any());
    }

    @Test
    void listRepositories_emptyToken_throws() {
        assertThatThrownBy(() -> service.listRepositories("https://dev.azure.com/myorg", null, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void listRepositories_blankBaseUrl_throws() {
        assertThatThrownBy(() -> service.listRepositories("  ", null, "tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base URL");
    }

    @Test
    void listRepositories_apiError_throws() {
        when(azureDevOpsApiClient.getOrganizationRepositories(
                        any(UriBuilderFactory.class), eq("7.1"), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.internalServerError().build());

        assertThatThrownBy(() -> service.listRepositories("https://dev.azure.com/myorg", null, "tok"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Azure DevOps");
    }

    @Test
    void readFile_returnsRawBody() {
        when(azureDevOpsApiClient.getItem(
                        any(UriBuilderFactory.class),
                        eq("proj"),
                        eq("repo"),
                        eq("src/a.txt"),
                        eq("main"),
                        eq(true),
                        eq("7.1"),
                        eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.ok("raw content"));

        AzureDevOpsApiService.Coordinates coords =
                new AzureDevOpsApiService.Coordinates("https://dev.azure.com/myorg", "myorg", "proj", "repo");

        String result = service.readFile(coords, "src/a.txt", "main", "tok");

        assertThat(result).isEqualTo("raw content");
    }

    @Test
    void readFile_emptyToken_throws() {
        AzureDevOpsApiService.Coordinates coords =
                new AzureDevOpsApiService.Coordinates("https://dev.azure.com/myorg", "myorg", "proj", "repo");

        assertThatThrownBy(() -> service.readFile(coords, "a.txt", "main", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void readFile_emptyResponse_throws() {
        when(azureDevOpsApiClient.getItem(
                        any(UriBuilderFactory.class), any(), any(), any(), any(), eq(true), any(), any()))
                .thenReturn(ResponseEntity.ok(null));

        AzureDevOpsApiService.Coordinates coords =
                new AzureDevOpsApiService.Coordinates("https://dev.azure.com/myorg", "myorg", "proj", "repo");

        assertThatThrownBy(() -> service.readFile(coords, "a.txt", "main", "tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void parse_devAzureComUrl() {
        AzureDevOpsApiService.Coordinates coords =
                AzureDevOpsApiService.parse("https://dev.azure.com/myorg/myproject/_git/repo");

        assertThat(coords.organization()).isEqualTo("myorg");
        assertThat(coords.project()).isEqualTo("myproject");
        assertThat(coords.repository()).isEqualTo("repo");
        assertThat(coords.apiBaseUrl()).isEqualTo("https://dev.azure.com/myorg");
    }

    @Test
    void parse_visualStudioComUrl() {
        AzureDevOpsApiService.Coordinates coords =
                AzureDevOpsApiService.parse("https://myorg.visualstudio.com/myproject/_git/repo");

        assertThat(coords.organization()).isEqualTo("myorg");
        assertThat(coords.project()).isEqualTo("myproject");
        assertThat(coords.repository()).isEqualTo("repo");
        assertThat(coords.apiBaseUrl()).isEqualTo("https://myorg.visualstudio.com");
    }

    @Test
    void parse_sshUrl() {
        AzureDevOpsApiService.Coordinates coords =
                AzureDevOpsApiService.parse("git@ssh.dev.azure.com:v3/myorg/myproject/repo");

        assertThat(coords.organization()).isEqualTo("myorg");
        assertThat(coords.project()).isEqualTo("myproject");
        assertThat(coords.repository()).isEqualTo("repo");
        assertThat(coords.apiBaseUrl()).isEqualTo("https://dev.azure.com/myorg");
    }

    @Test
    void parse_selfHostedServerUrl() {
        AzureDevOpsApiService.Coordinates coords =
                AzureDevOpsApiService.parse("https://azure.mycompany.com/tfs/DefaultCollection/myproject/_git/repo");

        assertThat(coords.organization()).isEqualTo("DefaultCollection");
        assertThat(coords.project()).isEqualTo("myproject");
        assertThat(coords.repository()).isEqualTo("repo");
        assertThat(coords.apiBaseUrl()).isEqualTo("https://azure.mycompany.com/tfs/DefaultCollection");
    }

    @Test
    void parse_selfHostedServerUrl_withoutVirtualDirectory() {
        AzureDevOpsApiService.Coordinates coords =
                AzureDevOpsApiService.parse("https://azure.mycompany.com/DefaultCollection/myproject/_git/repo");

        assertThat(coords.organization()).isEqualTo("DefaultCollection");
        assertThat(coords.project()).isEqualTo("myproject");
        assertThat(coords.apiBaseUrl()).isEqualTo("https://azure.mycompany.com/DefaultCollection");
    }

    @Test
    void parse_invalidUrl_throws() {
        assertThatThrownBy(() -> AzureDevOpsApiService.parse("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to parse");
    }

    @Test
    void findRepository_returnsEmptyOn404() {
        when(azureDevOpsApiClient.getRepository(
                        any(UriBuilderFactory.class), eq("proj"), eq("repo"), eq("7.1"), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.status(404).body("{}"));

        assertThat(service.findRepository("https://dev.azure.com/myorg", "proj", "repo", "tok"))
                .isEmpty();
    }

    @Test
    void findRepository_returnsRepository() {
        when(azureDevOpsApiClient.getRepository(
                        any(UriBuilderFactory.class), eq("proj"), eq("repo"), eq("7.1"), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.ok(
                        "{\"name\": \"repo\", \"remoteUrl\": \"https://dev.azure.com/myorg/proj/_git/repo\","
                                + "\"defaultBranch\": \"refs/heads/main\", \"project\": {\"name\": \"proj\"}}"));

        RemoteRepositoryDto result = service.findRepository("https://dev.azure.com/myorg", "proj", "repo", "tok")
                .orElseThrow();

        assertThat(result.name()).isEqualTo("proj/repo");
        assertThat(result.defaultBranch()).isEqualTo("main");
    }

    @Test
    void repositoryHasCommits_reflectsCount() {
        when(azureDevOpsApiClient.getCommits(
                        any(UriBuilderFactory.class), eq("proj"), eq("repo"), eq(1), eq("7.1"), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.ok("{\"count\": 1, \"value\": []}"))
                .thenReturn(ResponseEntity.ok("{\"count\": 0, \"value\": []}"));

        assertThat(service.repositoryHasCommits("https://dev.azure.com/myorg", "proj", "repo", "tok"))
                .isTrue();
        assertThat(service.repositoryHasCommits("https://dev.azure.com/myorg", "proj", "repo", "tok"))
                .isFalse();
    }

    @Test
    void createRepository_postsAndParsesResult() {
        when(azureDevOpsApiClient.createRepository(
                        any(UriBuilderFactory.class), eq("proj"), eq("7.1"), any(), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.status(201)
                        .body("{\"name\": \"repo\", \"remoteUrl\": \"https://dev.azure.com/myorg/proj/_git/repo\","
                                + "\"defaultBranch\": \"refs/heads/main\", \"project\": {\"name\": \"proj\"}}"));

        RemoteRepositoryDto result = service.createRepository(
                "https://dev.azure.com/myorg",
                "proj",
                new CreateRepositoryCommand("repo", RepositoryVisibility.PRIVATE, "main"),
                "tok");

        assertThat(result.cloneUrl()).isEqualTo("https://dev.azure.com/myorg/proj/_git/repo");
    }

    @Test
    void createRepository_errorStatus_throws() {
        when(azureDevOpsApiClient.createRepository(
                        any(UriBuilderFactory.class), eq("proj"), eq("7.1"), any(), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.status(400).body("{}"));

        CreateRepositoryCommand command = new CreateRepositoryCommand("repo", RepositoryVisibility.PRIVATE, "main");
        assertThatThrownBy(() -> service.createRepository("https://dev.azure.com/myorg", "proj", command, "tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creation failed");
    }
}
