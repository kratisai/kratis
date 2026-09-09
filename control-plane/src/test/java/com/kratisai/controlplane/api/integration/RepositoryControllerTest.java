package com.kratisai.controlplane.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.RegisterRepositoryRequest;
import com.kratisai.controlplane.api.restdto.UpdateRepositoryRequest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
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
class RepositoryControllerTest {

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
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    private String authToken;
    private UUID teamId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        teamId = ctx.team().getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
    }

    @Test
    void registerRepository_withCredentialId_shouldReturn201() throws Exception {
        RepoCredential credential = new RepoCredential();
        credential.setTeam(teamRepository.findById(teamId).orElseThrow());
        credential.setName("Test Cred");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret("enc");
        credential = repoCredentialRepository.save(credential);

        RegisterRepositoryRequest request = new RegisterRepositoryRequest(
                "credential-project",
                "https://github.com/user/credential-project.git",
                "main",
                RepositoryType.GITHUB,
                credential.getId());

        mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("credential-project"))
                .andExpect(jsonPath("$.credentialId").value(credential.getId().toString()));
    }

    @Test
    void registerRepository_validRequest_shouldReturn201() throws Exception {
        RegisterRepositoryRequest request = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);

        mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("my-project"))
                .andExpect(jsonPath("$.url").value("https://github.com/user/my-project.git"))
                .andExpect(jsonPath("$.ingestionStatus").doesNotExist());
    }

    @Test
    void listRepositories_shouldReturnReposForTeam() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest request = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);
        mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // List repos
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("my-project"));
    }

    @Test
    void getRepository_validRequest_shouldReturn200() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest request = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);
        String repoId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Get repo details
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("my-project"))
                .andExpect(jsonPath("$.id").value(repoId));
    }

    @Test
    void getRepository_nonExistent_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}", teamId, nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRepository_validRequest_shouldReturn200() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest createRequest = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);
        String repoId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Update repo
        UpdateRepositoryRequest updateRequest = new UpdateRepositoryRequest("updated-project", "develop", null, null);
        mockMvc.perform(put("/api/v1/teams/{teamId}/repositories/{repoId}", teamId, repoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated-project"))
                .andExpect(jsonPath("$.branch").value("develop"));
    }

    @Test
    void deleteRepository_validRequest_shouldReturn204() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest request = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);
        String repoId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Delete repo
        mockMvc.perform(delete("/api/v1/teams/{teamId}/repositories/{repoId}", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Verify it's gone
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRepository_withActiveBatch_shouldReturnCommitHash() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest createRequest = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);
        String repoId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Create an active batch with commit hash
        Repository repo = repositoryRepository.findById(UUID.fromString(repoId)).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.setCommitHash("abc123def456");
        batch.setActive(true);
        batch.setCompletedAt(java.time.Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        // Get repo details and verify commit hash is returned
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("my-project"))
                .andExpect(jsonPath("$.id").value(repoId))
                .andExpect(jsonPath("$.commitHash").value("abc123def456"))
                .andExpect(jsonPath("$.ingestionStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.lastIngestedAt").exists());
    }

    @Test
    void listRepositories_withActiveBatch_shouldReturnCommitHash() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest createRequest = new RegisterRepositoryRequest(
                "my-project", "https://github.com/user/my-project.git", "main", RepositoryType.GITHUB, null);
        String repoId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Create an active batch with commit hash
        Repository repo = repositoryRepository.findById(UUID.fromString(repoId)).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.setCommitHash("def789");
        batch.setActive(true);
        batch.setCompletedAt(java.time.Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        // List repos and verify commit hash is returned
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("my-project"))
                .andExpect(jsonPath("$[0].commitHash").value("def789"))
                .andExpect(jsonPath("$[0].ingestionStatus").value("SUCCESS"));
    }

    @Test
    void listRepositories_withFailedBatch_shouldReturnFailedIngestionStatus() throws Exception {
        // Create a repo first
        RegisterRepositoryRequest createRequest = new RegisterRepositoryRequest(
                "failing-project", "https://github.com/user/failing-project.git", "main", RepositoryType.GITHUB, null);
        String repoId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/teams/{teamId}/repositories", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Create a failed batch
        Repository repo = repositoryRepository.findById(UUID.fromString(repoId)).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.FAILED);
        batch.setErrorMessage("Clone failed: authentication error");
        batch.setActive(false);
        batch.setCompletedAt(java.time.Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        // List repos and verify failed ingestion status is returned
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("failing-project"))
                .andExpect(jsonPath("$[0].ingestionStatus").value("FAILED"));
    }
}
