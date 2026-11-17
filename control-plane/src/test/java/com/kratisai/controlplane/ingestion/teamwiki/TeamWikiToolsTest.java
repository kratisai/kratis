package com.kratisai.controlplane.ingestion.teamwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class TeamWikiToolsTest {

    @Mock
    private CtxWikiPageRepository wikiPageRepository;

    @Mock
    private CtxSurfaceRepository surfaceRepository;

    private ListTeamWikiPagesTool listTool;
    private ReadTeamWikiPageTool readTool;
    private DeleteTeamWikiPageTool deleteTool;
    private TeamWikiRepoReadTools repoReadTools;

    private UUID twBatchId;
    private UUID repoBatchId;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        listTool = new ListTeamWikiPagesTool(wikiPageRepository);
        readTool = new ReadTeamWikiPageTool(wikiPageRepository);
        deleteTool = new DeleteTeamWikiPageTool(wikiPageRepository);
        repoReadTools = new TeamWikiRepoReadTools(wikiPageRepository, surfaceRepository);
        twBatchId = UUID.randomUUID();
        repoBatchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of(
                TeamWikiContext.BATCH_ID_KEY,
                twBatchId,
                TeamWikiContext.REPO_BATCHES_KEY,
                Map.of("orders-api", repoBatchId)));
    }

    @Test
    void listAndReadTeamWikiPages_returnHierarchyAndContent() {
        CtxWikiPage root = new CtxWikiPage();
        root.setId(UUID.randomUUID());
        root.setPageSlug("root");
        root.setTitle("Root");
        root.setContent("Root body");

        CtxWikiPage child = new CtxWikiPage();
        child.setId(UUID.randomUUID());
        child.setPageSlug("cluster");
        child.setTitle("Cluster");

        when(wikiPageRepository.findByTeamWikiBatchIdAndParentPageIsNullOrderByOrderIndexAsc(twBatchId))
                .thenReturn(List.of(root));
        when(wikiPageRepository.findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(twBatchId, root.getId()))
                .thenReturn(List.of(child));
        when(wikiPageRepository.findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(twBatchId, child.getId()))
                .thenReturn(List.of());
        when(wikiPageRepository.findByTeamWikiBatchIdAndPageSlug(twBatchId, "root"))
                .thenReturn(Optional.of(root));

        assertThat(listTool.listTeamWikiPages(toolContext))
                .contains("- root (Root)")
                .contains("  - cluster (Cluster)");
        assertThat(readTool.readTeamWikiPage("root", toolContext))
                .contains("Title: Root")
                .contains("Root body");
        assertThatThrownBy(() -> readTool.readTeamWikiPage("", toolContext)).isInstanceOf(KratisToolException.class);
    }

    @Test
    void deleteTeamWikiPage_deletesChildrenRecursively() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setPageSlug("old-cluster");
        parent.setTitle("Old Cluster");
        CtxWikiPage child = new CtxWikiPage();
        child.setId(UUID.randomUUID());
        child.setPageSlug("old-child");

        when(wikiPageRepository.findByTeamWikiBatchIdAndPageSlug(twBatchId, "old-cluster"))
                .thenReturn(Optional.of(parent));
        when(wikiPageRepository.findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(twBatchId, parent.getId()))
                .thenReturn(List.of(child));
        when(wikiPageRepository.findByTeamWikiBatchIdAndParentPageIdOrderByOrderIndexAsc(twBatchId, child.getId()))
                .thenReturn(List.of());

        String result = deleteTool.deleteTeamWikiPage("old-cluster", toolContext);
        assertThat(result).contains("2 page(s) removed");
        verify(wikiPageRepository).delete(child);
        verify(wikiPageRepository).delete(parent);
    }

    @Test
    void repoReadTools_listReadPagesAndSurfaces() {
        CtxWikiPage repoPage = new CtxWikiPage();
        repoPage.setId(UUID.randomUUID());
        repoPage.setPageSlug("arch");
        repoPage.setTitle("Architecture");
        repoPage.setContent("Arch details");

        when(wikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(repoBatchId))
                .thenReturn(List.of(repoPage));
        when(wikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(repoBatchId, repoPage.getId()))
                .thenReturn(List.of());
        when(wikiPageRepository.findByBatchIdAndPageSlug(repoBatchId, "arch")).thenReturn(Optional.of(repoPage));

        CtxSurface surface = new CtxSurface(
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                SurfaceRole.PROVIDES,
                "Orders REST API",
                SurfaceDetection.CMM);
        CtxSurfaceAttribute attr =
                new CtxSurfaceAttribute(surface, AttributeKind.ENDPOINT, "/api/orders", SurfaceDetection.CMM);
        surface.getAttributeRows().add(attr);
        when(surfaceRepository.findByBatchIdIn(List.of(repoBatchId))).thenReturn(List.of(surface));

        assertThat(repoReadTools.listRepoWikiPages("orders-api", toolContext)).contains("arch (Architecture)");
        assertThat(repoReadTools.readRepoWikiPage("orders-api", "arch", toolContext))
                .contains("Arch details");
        assertThat(repoReadTools.getRepoSurfaces("orders-api", toolContext))
                .contains("[PROVIDES] Orders REST API (/api/orders)");
        assertThatThrownBy(() -> repoReadTools.listRepoWikiPages("unknown", toolContext))
                .isInstanceOf(KratisToolException.class);
    }
}
