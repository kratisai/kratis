package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.AzureDevOpsApiClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

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
        when(azureDevOpsApiClient.getRepositories(any(URI.class), eq(basicAuthHeader())))
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
        when(azureDevOpsApiClient.getRepositories(any(URI.class), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.ok(json));

        service.listRepositories("https://dev.azure.com/myorg", null, "tok");

        org.mockito.ArgumentCaptor<URI> captor = org.mockito.ArgumentCaptor.forClass(URI.class);
        verifyGetRepositories(captor);
        assertThat(captor.getValue().toString()).doesNotContain("/proj/_apis");
    }

    private void verifyGetRepositories(org.mockito.ArgumentCaptor<URI> captor) {
        org.mockito.Mockito.verify(azureDevOpsApiClient).getRepositories(captor.capture(), eq(basicAuthHeader()));
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
        when(azureDevOpsApiClient.getRepositories(any(URI.class), eq(basicAuthHeader())))
                .thenReturn(ResponseEntity.internalServerError().build());

        assertThatThrownBy(() -> service.listRepositories("https://dev.azure.com/myorg", null, "tok"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Azure DevOps");
    }

    @Test
    void readFile_returnsRawBody() {
        when(azureDevOpsApiClient.getItem(any(URI.class), eq(basicAuthHeader())))
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
        when(azureDevOpsApiClient.getItem(any(URI.class), eq(basicAuthHeader())))
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
}
