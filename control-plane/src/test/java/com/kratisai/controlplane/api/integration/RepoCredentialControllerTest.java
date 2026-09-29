package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeGitHubApiClientConfig.FakeGitHubApiClient;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.rest.RepoCredentialController;
import com.kratisai.controlplane.api.restdto.SaveRepoCredentialRequest;
import com.kratisai.controlplane.config.GitHubAppConfig;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.RepoCredentialRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.service.CredentialService;
import com.kratisai.controlplane.service.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class RepoCredentialControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private RepoCredentialRepository repoCredentialRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private GitHubAppConfig gitHubAppConfig;

    @Autowired
    private FakeGitHubApiClient fakeGitHubApiClient;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    private String authToken;
    private UUID teamId;
    private Team team;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        fakeGitHubApiClient.reset();
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();
        teamId = team.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
    }

    @Test
    void saveCredential_validRequest_shouldReturn201() throws Exception {
        SaveRepoCredentialRequest request =
                new SaveRepoCredentialRequest("My SSH Key", "SSH_KEY", "private-key-data", "ssh-rsa AAA...", "{}");

        mockMvc.perform(post("/api/v1/teams/{teamId}/credentials", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My SSH Key"))
                .andExpect(jsonPath("$.type").value("SSH_KEY"))
                .andExpect(jsonPath("$.publicKey").value("ssh-rsa AAA..."));
    }

    @Test
    void listCredentials_shouldReturnList() throws Exception {
        // Save a credential first
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("GitLab PAT Token");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret(credentialService.encrypt("encrypted-data"));
        credential.setPublicKey("ssh-rsa...");
        credential.setProviderMetadata("{}");
        repoCredentialRepository.save(credential);

        mockMvc.perform(get("/api/v1/teams/{teamId}/credentials", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("GitLab PAT Token"));
    }

    @Test
    void updateCredential_validRequest_shouldReturn200() throws Exception {
        // Save a credential first
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("Old Token");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret(credentialService.encrypt("encrypted-data"));
        repoCredentialRepository.save(credential);

        SaveRepoCredentialRequest request =
                new SaveRepoCredentialRequest("New Token", "GITLAB", "new-secret", null, "{\"user\":\"dev\"}");

        mockMvc.perform(put("/api/v1/teams/{teamId}/credentials/{credentialId}", teamId, credential.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Token"))
                .andExpect(jsonPath("$.providerMetadata").value("{\"user\":\"dev\"}"));
    }

    @Test
    void deleteCredential_shouldReturn204() throws Exception {
        // Save a credential first
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("Delete Me");
        credential.setType(CredentialType.SSH_KEY);
        repoCredentialRepository.save(credential);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/credentials/{credentialId}", teamId, credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        assertThat(repoCredentialRepository.findById(credential.getId())).isEmpty();
    }

    @Test
    void listAvailableRemoteRepositories_GitLabPat_shouldReturnRepos() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("GitLab Token");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret(credentialService.encrypt("encrypted-data"));
        credential.setProviderMetadata("{\"provider\":\"gitlab\"}");
        repoCredentialRepository.save(credential);

        Repository repository =
                new Repository("gitlab-repo", "https://gitlab.com/test/repo.git", "main", RepositoryType.GITLAB);
        repository.setCredential(credential);
        repository.setTeam(team);
        repositoryRepository.save(repository);

        // The fake client returns kratisai/gitlab-core
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/credentials/{credentialId}/available-repos",
                                teamId,
                                credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("kratisai/gitlab-core"));
    }

    @Test
    void listAvailableRemoteRepositories_UnsupportedType_shouldReturn400() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("SSH Key Auth");
        credential.setType(CredentialType.SSH_KEY);
        repoCredentialRepository.save(credential);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/credentials/{credentialId}/available-repos",
                                teamId,
                                credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getGitHubAppInfo_shouldReturnAppConfig() throws Exception {
        mockMvc.perform(get("/api/v1/config/github-app").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(gitHubAppConfig.getAppId()))
                .andExpect(jsonPath("$.appName").value(gitHubAppConfig.getAppName()))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.installationUrl").value(gitHubAppConfig.getInstallationUrl()));
    }

    @Test
    void listAvailableRemoteRepositories_GitHubApp_shouldReturnRepos() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("GitHub App Install");
        credential.setType(CredentialType.GITHUB_APP);
        credential.setProviderMetadata("{\"installationId\":\"456\",\"provider\":\"github\"}");
        repoCredentialRepository.save(credential);

        Repository repository =
                new Repository("github-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repository.setCredential(credential);
        repository.setTeam(team);
        repositoryRepository.save(repository);

        // The fake client returns repo1
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/credentials/{credentialId}/available-repos",
                                teamId,
                                credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("repo1"));
    }

    @Test
    void listAvailableRemoteRepositories_GitHubPat_withoutLinkedRepos_shouldReturnRepos() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("GitHub PAT");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret(credentialService.encrypt("encrypted-data"));
        // Provider is derived from metadata even though no repository is linked
        credential.setProviderMetadata("{\"provider\":\"github\"}");
        repoCredentialRepository.save(credential);

        // The fake client returns user-repo1 for the /user/repos endpoint
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/credentials/{credentialId}/available-repos",
                                teamId,
                                credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("user-repo1"));
    }

    @Test
    void listAvailableRemoteRepositories_BitbucketPat_shouldReturnRepos() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("Bitbucket Token");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret(credentialService.encrypt("encrypted-data"));
        credential.setProviderMetadata("{\"provider\":\"bitbucket\"}");
        repoCredentialRepository.save(credential);

        // The fake client returns kratisai/bitbucket-core
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/credentials/{credentialId}/available-repos",
                                teamId,
                                credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("kratisai/bitbucket-core"));
    }

    @Test
    void listAvailableRemoteRepositories_AzurePat_shouldReturnRepos() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName("Azure Token");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret(credentialService.encrypt("encrypted-data"));
        credential.setProviderMetadata("{\"provider\":\"azure\",\"azureBaseUrl\":\"https://dev.azure.com/kratisai\"}");
        repoCredentialRepository.save(credential);

        // The fake client returns project/azure-core
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/credentials/{credentialId}/available-repos",
                                teamId,
                                credential.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("project/azure-core"));
    }

    @Test
    void requireTeamMembership_shouldReturn403WhenNotMember() throws Exception {
        // Create an untied team
        Team otherTeam = new Team("Other Team", "No access");
        teamRepository.save(otherTeam);

        SaveRepoCredentialRequest request =
                new SaveRepoCredentialRequest("Leaked SSH Key", "SSH_KEY", "key-data", null, null);

        mockMvc.perform(post("/api/v1/teams/{teamId}/credentials", otherTeam.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void generateSshKey_shouldReturnSavedCredential() throws Exception {
        RepoCredentialController.GenerateSshKeyRequest req =
                new RepoCredentialController.GenerateSshKeyRequest("Auto SSH Key");

        mockMvc.perform(post("/api/v1/teams/{teamId}/credentials/generate-ssh-key", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Auto SSH Key"))
                .andExpect(jsonPath("$.type").value("SSH_KEY"))
                .andExpect(jsonPath("$.publicKey").value(org.hamcrest.Matchers.startsWith("ssh-rsa ")))
                .andExpect(jsonPath("$.privateKey").doesNotExist());
    }
}
