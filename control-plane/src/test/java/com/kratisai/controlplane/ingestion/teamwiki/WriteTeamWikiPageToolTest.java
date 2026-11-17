package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
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
        tool = new WriteTeamWikiPageTool(ctxWikiPageRepository, teamWikiBatchRepository, new MermaidDiagramValidator());
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
    void writeTeamWikiPage_validatesRepoWikiLinksAndSavesPage() {
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(repoBatchId, "architecture"))
                .thenReturn(Optional.of(new CtxWikiPage()));
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());

        String result = tool.writeTeamWikiPage(
                "overview",
                "Team Overview",
                "See [Orders API](/wiki/repo/orders-api) and [Arch](/wiki/repo/orders-api/architecture).",
                null,
                toolContext);

        assertThat(result).contains("SUCCESS");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTeamWikiBatch()).isEqualTo(batch);
        assertThat(captor.getValue().getRepoName()).isNull();
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
                "See [Booking](/wiki/repo/glark-newco/BookingService) and"
                        + " [Details](/wiki/repo/glark-newco/BookingService/booking-service).",
                null,
                toolContext);

        assertThat(result).contains("SUCCESS");
    }

    @Test
    void writeTeamWikiPage_rejectsUnknownRepoLink() {
        assertThatThrownBy(() -> tool.writeTeamWikiPage(
                        "overview", "Overview", "See [Missing](/wiki/repo/unknown-repo).", null, toolContext))
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
                        "overview", "Overview", "See [Broken](/wiki/repo/orders-api/missing-slug).", null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("missing-slug")
                .hasMessageContaining("overview");
    }

    @Test
    void writeTeamWikiPage_rejectsUnknownParentPageSlug() {
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "missing-parent"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByTeamWikiBatchId(batchId)).thenReturn(List.of());

        assertThatThrownBy(() -> tool.writeTeamWikiPage("child", "Child", "Body", "missing-parent", toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("missing-parent");
    }

    @Test
    void writeTeamWikiPage_updatesExistingPage() {
        CtxWikiPage existing = new CtxWikiPage(batch, batch.getTeam().getId(), null, "overview", "Old", 0, "Old");
        when(teamWikiBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByTeamWikiBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        String result = tool.writeTeamWikiPage("overview", "New Title", "New body", null, toolContext);

        assertThat(result).contains("updated successfully");
        verify(ctxWikiPageRepository).saveAndFlush(any(CtxWikiPage.class));
        assertThat(existing.getTitle()).isEqualTo("New Title");
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

        assertThatThrownBy(() -> tool.writeTeamWikiPage("root", "Root", "Updated", "child", toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("descendant");
    }
}
