package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.GitHubApiClient;
import com.kratisai.controlplane.config.GitHubAppConfig;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

class GitHubApiServiceTest {

    private GitHubApiService gitHubApiService;

    private final GitHubApiClient gitHubApiClient = Mockito.mock(GitHubApiClient.class);

    private final GitHubAppConfig gitHubAppConfig = Mockito.mock(GitHubAppConfig.class);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        gitHubApiService = new GitHubApiService(gitHubApiClient, gitHubAppConfig);

        // Generate a valid RSA private key dynamically for the test
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        byte[] privateKeyBytes = kp.getPrivate().getEncoded();
        String b64 = Base64.getEncoder().encodeToString(privateKeyBytes);
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";

        Path keyPath = tempDir.resolve("dummy-key.pem");
        Files.writeString(keyPath, pem);

        Mockito.when(gitHubAppConfig.getAppId()).thenReturn("test-app-id");
        Mockito.when(gitHubAppConfig.getPrivateKeyPath()).thenReturn(keyPath.toString());
    }

    @Test
    void shouldListAvailableRepositoriesSuccessfully() {
        String tokenResponse = "{\"token\": \"v1.1234567890\"}";
        Mockito.when(gitHubApiClient.createInstallationAccessToken(Mockito.eq("inst-123"), Mockito.anyString()))
                .thenReturn(ResponseEntity.ok(tokenResponse));

        String reposResponsePage1 = """
                        {
                          "total_count": 2,
                          "repositories": [
                            {
                              "name": "repo-1",
                              "full_name": "test-org/repo-1",
                              "clone_url": "https://github.com/test-org/repo-1.git",
                              "default_branch": "main"
                            }
                          ]
                        }""";

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Link", "<https://api.github.com/installation/repositories?per_page=100&page=2>; rel=\"next\"");

        Mockito.when(gitHubApiClient.getRepositories(
                        Mockito.eq(URI.create("https://api.github.com/installation/repositories?per_page=100")),
                        Mockito.anyString()))
                .thenReturn(ResponseEntity.ok().headers(headers).body(reposResponsePage1));

        String reposResponsePage2 = """
                        {
                          "total_count": 2,
                          "repositories": [
                            {
                              "name": "repo-2",
                              "full_name": "test-org/repo-2",
                              "clone_url": "https://github.com/test-org/repo-2.git",
                              "default_branch": "master"
                            }
                          ]
                        }""";

        Mockito.when(gitHubApiClient.getRepositories(
                        Mockito.eq(URI.create("https://api.github.com/installation/repositories?per_page=100&page=2")),
                        Mockito.anyString()))
                .thenReturn(ResponseEntity.ok(reposResponsePage2));

        List<RemoteRepositoryDto> repos = gitHubApiService.listAvailableRepositories("inst-123");

        assertThat(repos).hasSize(2);
        assertThat(repos.get(0).name()).isEqualTo("test-org/repo-1");
        assertThat(repos.get(0).defaultBranch()).isEqualTo("main");

        assertThat(repos.get(1).name()).isEqualTo("test-org/repo-2");
        assertThat(repos.get(1).defaultBranch()).isEqualTo("master");
    }

    @Test
    void shouldGetInstallationAccountLoginSuccessfully() {
        String installationResponse = "{\"account\": {\"login\": \"kratis-org\"}}";
        Mockito.when(gitHubApiClient.getInstallation(Mockito.eq("inst-123"), Mockito.anyString()))
                .thenReturn(ResponseEntity.ok(installationResponse));

        String login = gitHubApiService.getInstallationAccountLogin("inst-123");

        assertThat(login).isEqualTo("kratis-org");
    }

    @Test
    void shouldListUserRepositoriesSuccessfully() {
        String reposResponse = """
                        [
                          {
                            "name": "user-repo-1",
                            "full_name": "kratis-user/user-repo-1",
                            "clone_url": "https://github.com/kratis-user/user-repo-1.git",
                            "ssh_url": "git@github.com:kratis-user/user-repo-1.git",
                            "default_branch": "main"
                          }
                        ]""";
        Mockito.when(gitHubApiClient.getRepositories(
                        Mockito.eq(
                                URI.create(
                                        "https://api.github.com/user/repos?per_page=100&sort=full_name&affiliation=owner,collaborator,organization_member")),
                        Mockito.anyString()))
                .thenReturn(ResponseEntity.ok(reposResponse));

        List<RemoteRepositoryDto> repos = gitHubApiService.listUserRepositories("ghp_token");

        assertThat(repos).hasSize(1);
        assertThat(repos.getFirst().name()).isEqualTo("kratis-user/user-repo-1");
        assertThat(repos.getFirst().cloneUrl()).isEqualTo("https://github.com/kratis-user/user-repo-1.git");
        assertThat(repos.getFirst().sshUrl()).isEqualTo("git@github.com:kratis-user/user-repo-1.git");
    }
}
