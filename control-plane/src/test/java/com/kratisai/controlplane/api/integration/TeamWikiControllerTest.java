package com.kratisai.controlplane.api.integration;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiBatchLogService;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiSnapshotService;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiWorker;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiBuildMode;
import com.kratisai.controlplane.model.TeamWikiModelUsage;
import com.kratisai.controlplane.model.TeamWikiTriggerKind;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class TeamWikiControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TeamWikiBatchRepository teamWikiBatchRepository;

    @Autowired
    private CtxWikiPageRepository wikiPageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamWikiSnapshotService snapshotService;

    @Autowired
    private TeamWikiBatchLogService batchLogService;

    @MockitoBean
    private TeamWikiWorker teamWikiWorker;

    private String authToken;
    private UUID teamId;
    private Team team;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("TW API Team", "repo-alpha");
        team = ctx.team();
        teamId = team.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());

        IngestionBatch batch1 = ctx.batch();
        batch1.setStatus(IngestionStatus.SUCCESS);
        batch1.setCompletedAt(Instant.now());
        batch1.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch1);

        Repository repo2 = testDataFactory.createRepository(
                team, "repo-beta", TestDataFactory.getOrCreateDummyRepo(), "main", null);
        IngestionBatch batch2 = new IngestionBatch(repo2);
        batch2.setStatus(IngestionStatus.SUCCESS);
        batch2.setCommitHash("beta-commit");
        batch2.setCompletedAt(Instant.now());
        batch2.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch2);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void endpoints_returnPagesStatusLogsAndTriggerRebuild() throws Exception {
        TeamWikiBatch activeBatch = new TeamWikiBatch(
                team,
                TeamWikiBuildMode.FULL,
                TeamWikiTriggerKind.AUTOMATIC,
                snapshotService.captureCurrentState(teamId).snapshot());
        activeBatch.setStatus(IngestionStatus.SUCCESS);
        activeBatch.setActive(true);
        activeBatch.setCompletedAt(Instant.now());
        TeamWikiModelUsage chatUsage = new TeamWikiModelUsage(activeBatch, ModelKind.CHAT, "gpt-4o", "openai/gpt-4o");
        chatUsage.setVirtualKey("sk-test");
        activeBatch.getModelUsage().add(chatUsage);
        teamWikiBatchRepository.saveAndFlush(activeBatch);

        CtxWikiPage root = wikiPageRepository.saveAndFlush(
                new CtxWikiPage(activeBatch, teamId, null, "team-overview", "Team Overview", 0, "Root markdown"));
        CtxWikiPage child = wikiPageRepository.saveAndFlush(
                new CtxWikiPage(activeBatch, teamId, root, "core-cluster", "Core Cluster", 0, "Cluster markdown"));
        batchLogService.log(activeBatch.getId(), teamId, "INFO", "START", "Started team wiki build");

        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/status", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingestedRepoCount").value(2))
                .andExpect(jsonPath("$.dirty").value(false))
                .andExpect(
                        jsonPath("$.activeBatch.id").value(activeBatch.getId().toString()));

        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/pages", teamId).header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pageSlug").value("team-overview"))
                .andExpect(jsonPath("$[0].hasChildren").value(true));

        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/pages/{pageId}/children", teamId, root.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(child.getId().toString()));

        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/pages/{pageId}", teamId, child.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Cluster markdown"));

        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/batches/{batchId}/logs", teamId, activeBatch.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].step").value("START"));

        mockMvc.perform(post("/api/v1/teams/{teamId}/wiki/rebuild", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildMode").value("FULL"))
                .andExpect(jsonPath("$.triggerKind").value("MANUAL"));

        verify(teamWikiWorker).runBuild(org.mockito.ArgumentMatchers.any(UUID.class));

        // Second rebuild while one is QUEUED returns 409 Conflict
        mockMvc.perform(post("/api/v1/teams/{teamId}/wiki/rebuild", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"INCREMENTAL\"}"))
                .andExpect(status().isConflict());

        // Missing page & missing batch return 404
        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/pages/{pageId}", teamId, UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/batches/{batchId}/logs", teamId, UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpoints_forNonMember_returnForbidden() throws Exception {
        User outsider = new User();
        outsider.setEmail("outsider-" + UUID.randomUUID() + "@example.com");
        outsider.setDisplayName("Outsider");
        outsider.setPasswordHash("dummy-hash");
        outsider = userRepository.save(outsider);
        String outsiderToken = jwtService.generateAccessToken(outsider.getId(), outsider.getEmail());

        mockMvc.perform(get("/api/v1/teams/{teamId}/wiki/status", teamId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }
}
