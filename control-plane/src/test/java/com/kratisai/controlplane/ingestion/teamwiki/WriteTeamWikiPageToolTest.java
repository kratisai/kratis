package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.ingestion.write.WikiPageLinkExtractor;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.testsupport.WikiPageContentFixtures;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import com.kratisai.controlplane.validation.WikiPageContentValidator;
import com.kratisai.controlplane.validation.mermaid.MermaidNormalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class WriteTeamWikiPageToolTest {

    private static final String VALID_CONTENT = WikiPageContentFixtures.WIKI_PAGE_BODY;

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Mock
    private TeamWikiBatchRepository teamWikiBatchRepository;

    private WriteTeamWikiPageTool tool;
    private UUID batchId;
    private UUID repoBatchId;
    private TeamWikiBatch batch;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        tool = new WriteTeamWikiPageTool(
                ctxWikiPageRepository,
                teamWikiBatchRepository,
                new MermaidDiagramValidator(),
                new MermaidNormalizer(),
                new WikiPageContentValidator(),
                new WikiPageLinkExtractor());
        batchId = UUID.randomUUID();
        repoBatchId = UUID.randomUUID();
        Team team = new Team();
        team.setId(UUID.randomUUID());
        batch = new TeamWikiBatch();
        batch.setId(batchId);
        batch.setTeam(team);
        toolContext = new ToolContext(Map.of(
                TeamWikiContext.BATCH_ID_KEY,
                batchId,
                TeamWikiContext.REPO_BATCHES_KEY,
                Map.of("orders-api", repoBatchId, "glark-newco/BookingService", repoBatchId)));
    }

    @Test
    void writeTeamWikiPage_surgicallyFixesC4Commas() {
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        String content = VALID_CONTENT + """

                ```mermaid
                C4Context
                Person(player "Player")
                ```
                """;

        tool.writeTeamWikiPage("overview", "Team Overview", content, null, null, toolContext);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContent()).contains("Person(player, \"Player\")");
    }

    @Test
    void writeTeamWikiPage_validatesRepoWikiLinksAndSavesPage() {
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(repoBatchId, "architecture"))
                .thenReturn(Optional.of(new CtxWikiPage()));
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());

        String result = tool.writeTeamWikiPage(
                "overview",
                "Team Overview",
                VALID_CONTENT + " See [Orders API](/wiki/repo/orders-api) and"
                        + " [Arch](/wiki/repo/orders-api/architecture).",
                null,
                null,
                toolContext);

        assertThat(result).contains("SUCCESS");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTeamWikiBatch()).isEqualTo(batch);
        assertThat(captor.getValue().getRepoName()).isNull();
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(10);
    }

    @Test
    void writeTeamWikiPage_autoIncrementsOrderIndexAmongSiblings() {
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        CtxWikiPage sibling = new CtxWikiPage(batch, batch.getTeam().getId(), null, "sibling", "Sibling", 30, "Body");
        when(ctxWikiPageRepository.findByTeamWikiBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId))
                .thenReturn(List.of(sibling));

        tool.writeTeamWikiPage("overview", "Team Overview", VALID_CONTENT, null, null, toolContext);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(40);
    }

    @Test
    void writeTeamWikiPage_autoIncrementsChildOrderIndexAmongParentSiblings() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "parent"))
                .thenReturn(Optional.of(parent));
        CtxWikiPage sibling = new CtxWikiPage(batch, batch.getTeam().getId(), parent, "sibling", "Sibling", 10, "Body");
        when(ctxWikiPageRepository.findByTeamWikiBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId))
                .thenReturn(List.of(sibling));

        tool.writeTeamWikiPage("overview", "Cluster Detail", VALID_CONTENT, "parent", null, toolContext);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getParentPage()).isEqualTo(parent);
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(20);
    }

    @Test
    void writeTeamWikiPage_withExplicitOrderIndex_persistsIt() {
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());

        tool.writeTeamWikiPage("overview", "Team Overview", VALID_CONTENT, null, 25, toolContext);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(25);
    }

    @Test
    void writeTeamWikiPage_validatesSlashRepoWikiLinksAndSavesPage() {
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(repoBatchId, "booking-service"))
                .thenReturn(Optional.of(new CtxWikiPage()));
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());

        String result = tool.writeTeamWikiPage(
                "overview",
                "Team Overview",
                VALID_CONTENT + " See [Booking](/wiki/repo/glark-newco/BookingService) and"
                        + " [Details](/wiki/repo/glark-newco/BookingService/booking-service).",
                null,
                null,
                toolContext);

        assertThat(result).contains("SUCCESS");
    }

    @Test
    void writeTeamWikiPage_rejectsUnknownRepoLink() {
        assertThatThrownBy(() -> tool.writeTeamWikiPage(
                        "overview",
                        "Overview",
                        VALID_CONTENT + " See [Missing](/wiki/repo/unknown-repo).",
                        null,
                        null,
                        toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("unknown-repo");
    }

    @Test
    void writeTeamWikiPage_rejectsUnknownRepoPageSlug() {
        CtxWikiPage validPage = new CtxWikiPage();
        validPage.setPageSlug("overview");
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(repoBatchId, "missing-slug"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchId(repoBatchId)).thenReturn(List.of(validPage));

        assertThatThrownBy(() -> tool.writeTeamWikiPage(
                        "overview",
                        "Overview",
                        VALID_CONTENT + " See [Broken](/wiki/repo/orders-api/missing-slug).",
                        null,
                        null,
                        toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("missing-slug")
                .hasMessageContaining("overview");
    }

    @Test
    void writeTeamWikiPage_rejectsHeadingOnlyContent() {
        assertThatThrownBy(() -> tool.writeTeamWikiPage(
                        "authentication",
                        "Authentication & Security",
                        "# Authentication & Security\n",
                        null,
                        null,
                        toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("at least 80 words of prose")
                .hasMessageContaining("this page has 0");
    }

    @Test
    void writeTeamWikiPage_rejectsUnknownParentPageSlug() {
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "missing-parent"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByTeamWikiBatchId(batchId)).thenReturn(List.of());

        assertThatThrownBy(() ->
                        tool.writeTeamWikiPage("child", "Child", VALID_CONTENT, "missing-parent", null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("missing-parent");
    }

    @Test
    void writeTeamWikiPage_updatesExistingPageAndPreservesOrderIndex() {
        CtxWikiPage existing = new CtxWikiPage(batch, batch.getTeam().getId(), null, "overview", "Old", 7, "Old");
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        String result = tool.writeTeamWikiPage("overview", "New Title", VALID_CONTENT, null, null, toolContext);

        assertThat(result).contains("updated successfully");
        verify(ctxWikiPageRepository).saveAndFlush(any(CtxWikiPage.class));
        assertThat(existing.getTitle()).isEqualTo("New Title");
        assertThat(existing.getOrderIndex()).isEqualTo(7);
    }

    @Test
    void writeTeamWikiPage_updatesExistingPageWithExplicitOrderIndex() {
        CtxWikiPage existing = new CtxWikiPage(batch, batch.getTeam().getId(), null, "overview", "Old", 7, "Old");
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        tool.writeTeamWikiPage("overview", "New Title", VALID_CONTENT, null, 50, toolContext);

        assertThat(existing.getOrderIndex()).isEqualTo(50);
        verify(ctxWikiPageRepository).saveAndFlush(existing);
    }

    @Test
    void writeTeamWikiPage_updateWithoutParentSlug_keepsExistingParent() {
        CtxWikiPage parent = new CtxWikiPage(batch, batch.getTeam().getId(), null, "home", "Home", 0, "Home");
        parent.setId(UUID.randomUUID());
        CtxWikiPage existing = new CtxWikiPage(batch, batch.getTeam().getId(), parent, "child", "Old", 3, "Old");
        existing.setId(UUID.randomUUID());

        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "child"))
                .thenReturn(Optional.of(existing));

        tool.writeTeamWikiPage("child", "New Title", VALID_CONTENT, null, null, toolContext);

        assertThat(existing.getParentPage()).isSameAs(parent);
        assertThat(existing.getOrderIndex()).isEqualTo(3);
    }

    @Test
    void writeTeamWikiPage_updateWithExplicitParent_reparentsPage() {
        CtxWikiPage newParent = new CtxWikiPage(batch, batch.getTeam().getId(), null, "home", "Home", 0, "Home");
        newParent.setId(UUID.randomUUID());
        CtxWikiPage existing = new CtxWikiPage(batch, batch.getTeam().getId(), null, "child", "Old", 3, "Old");
        existing.setId(UUID.randomUUID());

        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "child"))
                .thenReturn(Optional.of(existing));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "home"))
                .thenReturn(Optional.of(newParent));

        tool.writeTeamWikiPage("child", "Child", VALID_CONTENT, "home", null, toolContext);

        assertThat(existing.getParentPage()).isSameAs(newParent);
    }

    @Test
    void writeTeamWikiPage_ignoresRepoLinksInsideCodeFences() {
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        String content = VALID_CONTENT + """

                ```markdown
                Link syntax is `[Repo](/wiki/repo/does-not-exist)`.
                ```

                | Column | Value |
                | --- | --- |
                | inline | `/wiki/repo/also-missing` |
                """;

        String result = tool.writeTeamWikiPage("overview", "Overview", content, null, null, toolContext);

        assertThat(result).contains("created successfully");
    }

    @Test
    void writeTeamWikiPage_rejectsReparentingToDescendantCycle() {
        CtxWikiPage root = new CtxWikiPage(batch, batch.getTeam().getId(), null, "root", "Root", 0, "Root");
        root.setId(UUID.randomUUID());
        CtxWikiPage child = new CtxWikiPage(batch, batch.getTeam().getId(), root, "child", "Child", 0, "Child");
        child.setId(UUID.randomUUID());

        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "child"))
                .thenReturn(Optional.of(child));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "root"))
                .thenReturn(Optional.of(root));

        assertThatThrownBy(() -> tool.writeTeamWikiPage("root", "Root", VALID_CONTENT, "child", null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("descendant");
    }
}
