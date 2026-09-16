package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
@SlowTest
class IngestionControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private IngestionBatchLogRepository ingestionBatchLogRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private String authToken;
    private UUID teamId;
    private UUID repoId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();
        teamId = team.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());

        // Create a repository for testing
        Repository repo =
                new Repository("test-repo", "https://github.com/user/test-repo.git", "main", RepositoryType.GITHUB);
        repo.setTeam(team);
        repositoryRepository.save(repo);
        repoId = repo.getId();
    }

    @Test
    void triggerIngestion_validRequest_shouldReturn202() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/repositories/{repoId}/ingest", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.queuePosition").value(1));

        awaitForIngestionToComplete(repoId);
    }

    @Test
    void triggerIngestion_nonExistentRepo_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/teams/{teamId}/repositories/{repoId}/ingest", teamId, nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void triggerIngestion_notTeamMember_shouldReturn403() throws Exception {
        // Create another team the user is not a member of
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        mockMvc.perform(post("/api/v1/teams/{teamId}/repositories/{repoId}/ingest", otherTeam.getId(), repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void triggerIngestion_shouldCreateBatchInDatabase() throws Exception {
        // Trigger ingestion and capture batch ID
        String response = mockMvc.perform(post("/api/v1/teams/{teamId}/repositories/{repoId}/ingest", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Verify batch was created in database and linked to the right repo
        UUID batchId =
                UUID.fromString(objectMapper.readTree(response).get("batchId").asText());
        IngestionBatch batch = ingestionBatchRepository.findById(batchId).orElseThrow();
        assertThat(batch.getRepository().getId()).isEqualTo(repoId);

        awaitForIngestionToComplete(repoId);
    }

    private void awaitForIngestionToComplete(UUID targetRepoId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> ingestionBatchRepository
                        .findFirstByRepositoryIdOrderByStartedAtDesc(targetRepoId)
                        .ifPresent(latestBatch -> assertThat(latestBatch.getStatus())
                                .isIn(IngestionStatus.SUCCESS, IngestionStatus.FAILED)));
    }

    @Test
    void getBatchHistory_noBatches_shouldReturnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getBatchHistory_withBatches_shouldReturnBatches() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch1 = new IngestionBatch(repo);
        batch1.setStatus(IngestionStatus.SUCCESS);
        batch1.setCommitHash("abc123");
        batch1.setCompletedAt(Instant.now());
        ingestionBatchRepository.saveAndFlush(batch1);

        IngestionBatch batch2 = new IngestionBatch(repo);
        batch2.setStatus(IngestionStatus.FAILED);
        batch2.setErrorMessage("Test error");
        ingestionBatchRepository.saveAndFlush(batch2);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                // batch2 was created after batch1, so it appears first (most recent)
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].errorMessage").value("Test error"))
                .andExpect(jsonPath("$[1].status").value("SUCCESS"))
                .andExpect(jsonPath("$[1].commitHash").value("abc123"));
    }

    @Test
    void getBatchHistory_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", otherTeam.getId(), repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBatchHistory_nonExistentRepo_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", teamId, nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBatchLogs_validRequest_shouldReturnLogs() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.PROCESSING);
        ingestionBatchRepository.saveAndFlush(batch);

        IngestionBatchLog log = new IngestionBatchLog(batch, teamId, "INFO", "CLONE", "Testing database audit logging");
        ingestionBatchLogRepository.saveAndFlush(log);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/logs",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].level").value("INFO"))
                .andExpect(jsonPath("$[0].step").value("CLONE"))
                .andExpect(jsonPath("$[0].message").value("Testing database audit logging"));
    }

    @Test
    void getBatchHistory_multipleIngestions_shouldReturnChronologicalOrder() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();

        // Create 5 batches with different statuses and timestamps
        Instant baseTime = Instant.now();
        for (int i = 0; i < 5; i++) {
            IngestionBatch batch = new IngestionBatch(repo);
            batch.setStatus(i % 2 == 0 ? IngestionStatus.SUCCESS : IngestionStatus.FAILED);
            batch.setCommitHash("commit" + i);
            batch.setStartedAt(baseTime.minusSeconds((long) (5 - i) * 60));
            batch.setCompletedAt(baseTime.minusSeconds((5 - i) * 60 - 30));
            if (i % 2 != 0) {
                batch.setErrorMessage("Error in batch " + i);
            }
            ingestionBatchRepository.saveAndFlush(batch);
            Thread.sleep(10); // Ensure distinct timestamps
        }

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5))
                // Most recent first (batch 4 was created last)
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].commitHash").value("commit4"))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].commitHash").value("commit3"))
                .andExpect(jsonPath("$[1].errorMessage").value("Error in batch 3"));
    }

    @Test
    void getBatchHistory_failedIngestion_shouldReturnErrorMessage() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.FAILED);
        batch.setCommitHash("deadbeef");
        batch.setErrorMessage("Clone failed: repository not found");
        batch.setStartedAt(Instant.now().minusSeconds(60));
        batch.setCompletedAt(Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].commitHash").value("deadbeef"))
                .andExpect(jsonPath("$[0].errorMessage").value("Clone failed: repository not found"))
                .andExpect(jsonPath("$[0].startedAt").exists())
                .andExpect(jsonPath("$[0].completedAt").exists());
    }

    @Test
    void getBatchHistory_processingIngestion_shouldReturnWithoutCompletedAt() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.PROCESSING);
        batch.setCommitHash("abc123");
        batch.setStartedAt(Instant.now());
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/batches", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$[0].startedAt").exists());
    }

    @Test
    void getBatchLogs_multipleLogs_shouldReturnOrderedByCreatedAt() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.PROCESSING);
        ingestionBatchRepository.saveAndFlush(batch);

        // Create logs with different levels
        IngestionBatchLog log1 = new IngestionBatchLog(batch, teamId, "INFO", "CLONE", "Starting clone");
        ingestionBatchLogRepository.saveAndFlush(log1);
        Thread.sleep(10);

        IngestionBatchLog log2 = new IngestionBatchLog(batch, teamId, "WARN", "PARSE", "Warning: deprecated syntax");
        ingestionBatchLogRepository.saveAndFlush(log2);
        Thread.sleep(10);

        IngestionBatchLog log3 = new IngestionBatchLog(batch, teamId, "ERROR", "INDEX", "Error: connection timeout");
        ingestionBatchLogRepository.saveAndFlush(log3);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/logs",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].level").value("INFO"))
                .andExpect(jsonPath("$[0].message").value("Starting clone"))
                .andExpect(jsonPath("$[1].level").value("WARN"))
                .andExpect(jsonPath("$[1].message").value("Warning: deprecated syntax"))
                .andExpect(jsonPath("$[2].level").value("ERROR"))
                .andExpect(jsonPath("$[2].message").value("Error: connection timeout"));
    }

    @Test
    void getBatchLogs_nonExistentBatch_shouldReturnEmptyList() throws Exception {
        UUID nonExistentBatchId = UUID.randomUUID();

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/logs",
                                teamId,
                                repoId,
                                nonExistentBatchId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getBatchLogs_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.PROCESSING);
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/logs",
                                otherTeam.getId(),
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBatchLogs_nonExistentRepo_shouldReturn404() throws Exception {
        UUID nonExistentRepoId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/logs",
                                teamId,
                                nonExistentRepoId,
                                batchId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBatchStats_validRequest_shouldReturnStats() throws Exception {
        // Create a batch with nodes and edges
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStartedAt(Instant.now().minusSeconds(120));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(batch);

        // Create nodes
        CtxNode funcNode = new CtxNode(batch, teamId, "test-repo", NodeType.FUNCTION, "src/main.py");
        funcNode.setSymbolName("myFunc");
        CtxNode classNode = new CtxNode(batch, teamId, "test-repo", NodeType.CLASS, "src/main.py");
        classNode.setSymbolName("MyClass");
        ctxNodeRepository.saveAllAndFlush(List.of(funcNode, classNode));

        // Create edge
        CtxEdge callEdge = new CtxEdge(batch, teamId, funcNode, classNode, RelationType.CALLS);
        ctxEdgeRepository.saveAllAndFlush(List.of(callEdge));

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batch.getId().toString()))
                .andExpect(jsonPath("$.totalNodes").value(2))
                .andExpect(jsonPath("$.totalEdges").value(1))
                .andExpect(jsonPath("$.totalDurationSeconds").value(120))
                .andExpect(jsonPath("$.nodeTypeCounts.function").value(1))
                .andExpect(jsonPath("$.nodeTypeCounts.class").value(1))
                .andExpect(jsonPath("$.edgeTypeCounts.calls").value(1));
    }

    @Test
    void getBatchStats_nonExistentBatch_shouldReturn404() throws Exception {
        UUID nonExistentBatchId = UUID.randomUUID();

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                nonExistentBatchId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBatchStats_notTeamMember_shouldReturn403() throws Exception {
        Team otherTeam = new Team("Other Team", "Not accessible");
        teamRepository.save(otherTeam);

        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                otherTeam.getId(),
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBatchStats_nonExistentRepo_shouldReturn404() throws Exception {
        UUID nonExistentRepoId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                nonExistentRepoId,
                                batchId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBatchStats_includesArchitecturePatterns() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStartedAt(Instant.now().minusSeconds(60));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(batch);

        CtxArchitecturePattern pattern1 =
                new CtxArchitecturePattern(batch, teamId, "Hexagonal", "Hexagonal Architecture pattern", List.of());
        CtxArchitecturePattern pattern2 = new CtxArchitecturePattern(
                batch, teamId, "MVC", "Model-View-Controller Architecture pattern", List.of());
        ctxArchitecturePatternRepository.saveAllAndFlush(List.of(pattern1, pattern2));

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architecturePatterns").isArray())
                .andExpect(jsonPath("$.architecturePatterns.length()").value(2))
                .andExpect(jsonPath("$.architecturePatterns[0].name").value("Hexagonal"))
                .andExpect(jsonPath("$.architecturePatterns[0].description").value("Hexagonal Architecture pattern"))
                .andExpect(jsonPath("$.architecturePatterns[1].name").value("MVC"))
                .andExpect(jsonPath("$.architecturePatterns[1].description")
                        .value("Model-View-Controller Architecture pattern"));
    }

    @Test
    void getBatchStats_emptyArchitecturePatterns_shouldReturnEmptyArray() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStartedAt(Instant.now().minusSeconds(60));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architecturePatterns").isArray())
                .andExpect(jsonPath("$.architecturePatterns.length()").value(0));
    }

    @Test
    void getBatchStats_includesUsageStats() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStartedAt(Instant.now().minusSeconds(60));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        IngestionModelUsage chatUsage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        chatUsage.apply(new LlmUsageSnapshot(0.04, 2000L, 1200L, 800L));
        batch.getModelUsage().add(chatUsage);
        IngestionModelUsage embeddingUsage =
                new IngestionModelUsage(batch, ModelKind.EMBEDDING, "text-embedding-3-small", "team-embed-alias");
        embeddingUsage.apply(new LlmUsageSnapshot(0.0056, 500L, 300L, 200L));
        batch.getModelUsage().add(embeddingUsage);
        batch.setTotalToolCalls(9L);
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpend").value(0.0456))
                .andExpect(jsonPath("$.totalTokens").value(2500))
                .andExpect(jsonPath("$.promptTokens").value(1500))
                .andExpect(jsonPath("$.completionTokens").value(1000))
                .andExpect(jsonPath("$.totalToolCalls").value(9))
                .andExpect(jsonPath("$.usageLastUpdatedAt").exists());
    }

    @Test
    void getBatchStats_withoutUsage_shouldReturnNullUsageFields() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStartedAt(Instant.now().minusSeconds(60));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpend").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.totalTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.promptTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.completionTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.totalToolCalls").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.usageLastUpdatedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void getBatchStats_emptyBatch_shouldReturnZeroCounts() throws Exception {
        Repository repo = repositoryRepository.findById(repoId).orElseThrow();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStartedAt(Instant.now().minusSeconds(60));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(batch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/batches/{batchId}/stats",
                                teamId,
                                repoId,
                                batch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalNodes").value(0))
                .andExpect(jsonPath("$.totalEdges").value(0))
                .andExpect(jsonPath("$.nodeTypeCounts").isEmpty())
                .andExpect(jsonPath("$.edgeTypeCounts").isEmpty());
    }
}
