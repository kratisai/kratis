package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
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
class ReadWikiPageToolTest {

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    private ReadWikiPageTool readWikiPageTool;

    private UUID batchId;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        readWikiPageTool = new ReadWikiPageTool(ctxWikiPageRepository);
        batchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of("batchId", batchId));
    }

    @Test
    void readWikiPage_withExistingPage_returnsFormattedContent() {
        CtxWikiPage page = new CtxWikiPage();
        page.setBatch(new IngestionBatch());
        page.setTeamId(UUID.randomUUID());
        page.setRepoName("test-repo");
        page.setPageSlug("overview");
        page.setTitle("System Overview");
        page.setContent("# Introduction\n\nThis is the overview.");

        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(page));

        String result = readWikiPageTool.readWikiPage("overview", toolContext);

        assertThat(result).isEqualTo("Title: System Overview\n\nContent:\n# Introduction\n\nThis is the overview.");
    }

    @Test
    void readWikiPage_withBlankSlug_returnsError() {
        String result = readWikiPageTool.readWikiPage("  ", toolContext);

        assertThat(result).isEqualTo("ERROR: pageSlug must not be blank.");
    }

    @Test
    void readWikiPage_withNullSlug_returnsError() {
        String result = readWikiPageTool.readWikiPage(null, toolContext);

        assertThat(result).isEqualTo("ERROR: pageSlug must not be blank.");
    }

    @Test
    void readWikiPage_withMissingBatchId_throwsException() {
        ToolContext emptyContext = new ToolContext(Map.of());

        assertThatThrownBy(() -> readWikiPageTool.readWikiPage("overview", emptyContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId not found");
    }

    @Test
    void readWikiPage_withNullToolContext_throwsException() {
        assertThatThrownBy(() -> readWikiPageTool.readWikiPage("overview", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId not found");
    }

    @Test
    void readWikiPage_withStringBatchId_returnsFormattedContent() {
        CtxWikiPage page = new CtxWikiPage();
        page.setBatch(new IngestionBatch());
        page.setTeamId(UUID.randomUUID());
        page.setRepoName("test-repo");
        page.setPageSlug("overview");
        page.setTitle("Title");
        page.setContent("Content");

        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(page));

        ToolContext stringBatchContext = new ToolContext(Map.of("batchId", batchId.toString()));
        String result = readWikiPageTool.readWikiPage("overview", stringBatchContext);

        assertThat(result).contains("Title").contains("Content");
    }

    @Test
    void readWikiPage_withInvalidBatchId_throwsException() {
        ToolContext invalidContext = new ToolContext(Map.of("batchId", "not-a-uuid"));

        assertThatThrownBy(() -> readWikiPageTool.readWikiPage("overview", invalidContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid batchId format");
    }

    @Test
    void readWikiPage_withNonExistentPage_returnsError() {
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "missing")).thenReturn(Optional.empty());

        String result = readWikiPageTool.readWikiPage("missing", toolContext);

        assertThat(result).isEqualTo("ERROR: Wiki page not found: missing");
    }
}
