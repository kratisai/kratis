package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
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
class WriteWikiPageToolTest {

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Mock
    private IngestionBatchRepository ingestionBatchRepository;

    private WriteWikiPageTool writeWikiPageTool;

    private UUID batchId;
    private ToolContext toolContext;
    private IngestionBatch batch;
    private Team team;
    private Repository repository;

    @BeforeEach
    void setUp() {
        writeWikiPageTool =
                new WriteWikiPageTool(ctxWikiPageRepository, ingestionBatchRepository, new MermaidDiagramValidator());
        batchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of("batchId", batchId));
        team = new Team();
        team.setId(UUID.randomUUID());
        repository = new Repository();
        repository.setTeam(team);
        batch = new IngestionBatch(repository);
        batch.setId(batchId);
    }

    @Test
    void writeWikiPage_withNewPage_createsPage() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());

        String result =
                writeWikiPageTool.writeWikiPage("overview", "System Overview", "# Overview content", null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        CtxWikiPage saved = captor.getValue();
        assertThat(saved.getBatch()).isEqualTo(batch);
        assertThat(saved.getTeamId()).isEqualTo(team.getId());
        assertThat(saved.getRepoName()).isEqualTo(repository.getName());
        assertThat(saved.getPageSlug()).isEqualTo("overview");
        assertThat(saved.getTitle()).isEqualTo("System Overview");
        assertThat(saved.getContent()).isEqualTo("# Overview content");
        assertThat(saved.getOrderIndex()).isZero();
        assertThat(saved.getParentPage()).isNull();
    }

    @Test
    void writeWikiPage_withExistingPage_updatesPage() {
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("overview");
        existing.setTitle("Old Title");
        existing.setContent("Old content");

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        String result = writeWikiPageTool.writeWikiPage("overview", "New Title", "New content", null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' updated successfully.");
        assertThat(existing.getTitle()).isEqualTo("New Title");
        assertThat(existing.getContent()).isEqualTo("New content");
        verify(ctxWikiPageRepository).saveAndFlush(existing);
    }

    @Test
    void writeWikiPage_withParentSlug_setsParentPage() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setBatch(batch);
        parent.setPageSlug("parent");

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "parent")).thenReturn(Optional.of(parent));

        String result = writeWikiPageTool.writeWikiPage("overview", "Child", "Child content", "parent", toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getParentPage()).isEqualTo(parent);
    }

    @Test
    void writeWikiPage_withBlankPageSlug_returnsError() {
        String result = writeWikiPageTool.writeWikiPage("  ", "Title", "Content", null, toolContext);

        assertThat(result).isEqualTo("ERROR: pageSlug must not be blank.");
    }

    @Test
    void writeWikiPage_withBlankTitle_returnsError() {
        String result = writeWikiPageTool.writeWikiPage("slug", "  ", "Content", null, toolContext);

        assertThat(result).isEqualTo("ERROR: title must not be blank.");
    }

    @Test
    void writeWikiPage_withBlankContent_returnsError() {
        String result = writeWikiPageTool.writeWikiPage("slug", "Title", "  ", null, toolContext);

        assertThat(result).isEqualTo("ERROR: content must not be blank.");
    }

    @Test
    void writeWikiPage_withUnquotedSubgraphTitleContainingParens_returnsError() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Control Plane Tier (Spring Boot)
                        API[REST Controllers]
                    end
                ```
                """;

        String result = writeWikiPageTool.writeWikiPage("overview", "Title", content, null, toolContext);

        assertThat(result).startsWith("ERROR: Invalid Mermaid diagram syntax in content.");
        assertThat(result).contains("subgraph title");
    }

    @Test
    void writeWikiPage_withQuotedSubgraphTitle_createsPage() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        String content = """
                ```mermaid
                graph TD
                    subgraph "Control Plane Tier (Spring Boot)"
                        API[REST Controllers]
                    end
                ```
                """;

        String result = writeWikiPageTool.writeWikiPage("overview", "Title", content, null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");
    }

    @Test
    void writeWikiPage_withMissingBatchId_throwsException() {
        ToolContext emptyContext = new ToolContext(Map.of());

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", "Content", null, emptyContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId not found");
    }

    @Test
    void writeWikiPage_withInvalidBatchId_throwsException() {
        ToolContext invalidContext = new ToolContext(Map.of("batchId", "not-a-uuid"));

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", "Content", null, invalidContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid batchId format");
    }

    @Test
    void writeWikiPage_withMissingBatch_throwsException() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", "Content", null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch not found");
    }

    @Test
    void writeWikiPage_withMissingParent_throwsException() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "slug")).thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "missing-parent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        writeWikiPageTool.writeWikiPage("slug", "Title", "Content", "missing-parent", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No wiki page exists matching parent slug");
    }
}
