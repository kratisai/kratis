package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class ListWikiPagesToolTest {

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    private ListWikiPagesTool listWikiPagesTool;

    private UUID batchId;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        listWikiPagesTool = new ListWikiPagesTool(ctxWikiPageRepository);
        batchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of("batchId", batchId));
    }

    @Test
    void listWikiPages_withNoPages_tellsModelToWriteRootFirst() {
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                .thenReturn(List.of());

        String result = listWikiPagesTool.listWikiPages(toolContext);

        assertThat(result).contains("No wiki pages have been written yet").contains("top-level page first");
    }

    @Test
    void listWikiPages_returnsHierarchyWithSlugsAndTitles() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        CtxWikiPage root = page(rootId, "overview", "Overview", null);
        CtxWikiPage child = page(childId, "architecture", "Architecture", root);

        when(ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                .thenReturn(List.of(root));
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, rootId))
                .thenReturn(List.of(child));
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, childId))
                .thenReturn(List.of());

        String result = listWikiPagesTool.listWikiPages(toolContext);

        assertThat(result).contains("- overview (Overview)");
        assertThat(result).contains("  - architecture (Architecture)");
    }

    @Test
    void listWikiPages_withStringBatchId_resolvesBatch() {
        UUID rootId = UUID.randomUUID();
        CtxWikiPage root = page(rootId, "overview", "Overview", null);
        ToolContext stringContext = new ToolContext(Map.of("batchId", batchId.toString()));

        when(ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                .thenReturn(List.of(root));
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, rootId))
                .thenReturn(List.of());

        assertThat(listWikiPagesTool.listWikiPages(stringContext)).contains("overview");
    }

    @Test
    void listWikiPages_withMissingBatchId_throwsException() {
        ToolContext emptyContext = new ToolContext(Map.of());

        assertThatThrownBy(() -> listWikiPagesTool.listWikiPages(emptyContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId not found");
    }

    @Test
    void listWikiPages_withInvalidBatchId_throwsException() {
        ToolContext invalidContext = new ToolContext(Map.of("batchId", "not-a-uuid"));

        assertThatThrownBy(() -> listWikiPagesTool.listWikiPages(invalidContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid batchId format");
    }

    private static CtxWikiPage page(UUID id, String slug, String title, CtxWikiPage parent) {
        CtxWikiPage page = new CtxWikiPage();
        page.setId(id);
        page.setPageSlug(slug);
        page.setTitle(title);
        page.setParentPage(parent);
        page.setContent("");
        return page;
    }
}
