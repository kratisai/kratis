package com.kratisai.controlplane.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class WikiControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private CtxWikiPageRepository wikiPageRepository;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    private String authToken;
    private UUID teamId;
    private UUID repoId;
    private IngestionBatch activeBatch;
    private Repository repo;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("Wiki Team", "wiki-repo");
        Team team = ctx.team();
        repo = ctx.repository();
        teamId = team.getId();
        repoId = repo.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());

        activeBatch = ctx.batch();
        activeBatch.setActive(true);
        ingestionBatchRepository.saveAndFlush(activeBatch);
    }

    private CtxWikiPage createPage(IngestionBatch batch, String slug, String title, int order) {
        return wikiPageRepository.save(
                new CtxWikiPage(batch, teamId, repo.getName(), null, slug, title, order, "Content of " + title));
    }

    private void createChildPage(IngestionBatch batch, CtxWikiPage parent, String slug, String title, int order) {
        wikiPageRepository.save(
                new CtxWikiPage(batch, teamId, repo.getName(), parent, slug, title, order, "Child content"));
    }

    // -------------------------------------------------------------------------
    // GET /pages — top-level pages
    // -------------------------------------------------------------------------

    @Test
    void getTopLevelPages_returnsOnlyActiveBatchPages() throws Exception {
        // Active batch page
        createPage(activeBatch, "overview", "Overview", 0);

        // Inactive batch with its own page
        IngestionBatch inactiveBatch = testDataFactory.createBatchForRepository(repo);
        inactiveBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(inactiveBatch);
        createPage(inactiveBatch, "old-overview", "Old Overview", 0);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Overview"));
    }

    @Test
    void getTopLevelPages_noActiveBatch_returns404() throws Exception {
        activeBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(activeBatch);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTopLevelPages_orderedByOrderIndex() throws Exception {
        createPage(activeBatch, "second", "Second Page", 1);
        createPage(activeBatch, "first", "First Page", 0);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("First Page"))
                .andExpect(jsonPath("$[1].title").value("Second Page"));
    }

    @Test
    void getTopLevelPages_hasChildrenFlag_isTrue_whenChildrenExist() throws Exception {
        CtxWikiPage parent = createPage(activeBatch, "parent", "Parent Page", 0);
        createChildPage(activeBatch, parent, "child", "Child Page", 0);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasChildren").value(true));
    }

    @Test
    void getTopLevelPages_hasChildrenFlag_isFalse_whenNoChildren() throws Exception {
        createPage(activeBatch, "leaf", "Leaf Page", 0);

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasChildren").value(false));
    }

    @Test
    void getTopLevelPages_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTopLevelPages_nonMember_returns403() throws Exception {
        TestDataFactory.TestContext other = testDataFactory.createUserAndTeam();
        String otherToken = jwtService.generateAccessToken(
                other.user().getId(), other.user().getEmail());

        mockMvc.perform(get("/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages", teamId, repoId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // GET /pages/{pageId}/children
    // -------------------------------------------------------------------------

    @Test
    void getChildPages_returnsOnlyActiveBatchChildren() throws Exception {
        CtxWikiPage activeParent = createPage(activeBatch, "parent", "Parent", 0);
        createChildPage(activeBatch, activeParent, "active-child", "Active Child", 0);

        // Inactive batch with a page having the same parent ID (different batch)
        IngestionBatch inactiveBatch = testDataFactory.createBatchForRepository(repo);
        inactiveBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(inactiveBatch);
        CtxWikiPage inactiveParent = createPage(inactiveBatch, "parent", "Parent", 0);
        createChildPage(inactiveBatch, inactiveParent, "old-child", "Old Child", 0);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages/{pageId}/children",
                                teamId,
                                repoId,
                                activeParent.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Active Child"));
    }

    @Test
    void getChildPages_noActiveBatch_returns404() throws Exception {
        activeBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(activeBatch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages/{pageId}/children",
                                teamId,
                                repoId,
                                UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /pages/{pageId}
    // -------------------------------------------------------------------------

    @Test
    void getPage_returnsPageFromActiveBatch() throws Exception {
        CtxWikiPage page = createPage(activeBatch, "intro", "Introduction", 0);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages/{pageId}",
                                teamId,
                                repoId,
                                page.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Introduction"))
                .andExpect(jsonPath("$.pageSlug").value("intro"));
    }

    @Test
    void getPage_pageFromInactiveBatch_returns404() throws Exception {
        IngestionBatch inactiveBatch = testDataFactory.createBatchForRepository(repo);
        inactiveBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(inactiveBatch);
        CtxWikiPage oldPage = createPage(inactiveBatch, "old-intro", "Old Introduction", 0);

        // The active batch exists but the page belongs to the inactive batch
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages/{pageId}",
                                teamId,
                                repoId,
                                oldPage.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPage_noActiveBatch_returns404() throws Exception {
        activeBatch.setActive(false);
        ingestionBatchRepository.saveAndFlush(activeBatch);

        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages/{pageId}",
                                teamId,
                                repoId,
                                UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPage_nonExistentPage_returns404() throws Exception {
        mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/repositories/{repoId}/wiki/pages/{pageId}",
                                teamId,
                                repoId,
                                UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }
}
