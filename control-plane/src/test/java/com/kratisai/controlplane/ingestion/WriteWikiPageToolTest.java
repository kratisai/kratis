package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.agentloop.KratisToolException;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.testsupport.WikiPageContentFixtures;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import com.kratisai.controlplane.validation.WikiPageContentValidator;
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
class WriteWikiPageToolTest {

    private static final String VALID_CONTENT = "# Overview\n\n" + WikiPageContentFixtures.WIKI_PAGE_BODY;

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
    private IngestionBatchLogService.BatchLogger wikiBatchLogger;

    @BeforeEach
    void setUp() {
        writeWikiPageTool = new WriteWikiPageTool(
                ctxWikiPageRepository,
                ingestionBatchRepository,
                new MermaidDiagramValidator(),
                new WikiPageContentValidator());
        batchId = UUID.randomUUID();
        wikiBatchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        toolContext = new ToolContext(Map.of("batchId", batchId, "wikiBatchLogger", wikiBatchLogger));
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
                writeWikiPageTool.writeWikiPage("overview", "System Overview", VALID_CONTENT, null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        CtxWikiPage saved = captor.getValue();
        assertThat(saved.getBatch()).isEqualTo(batch);
        assertThat(saved.getTeamId()).isEqualTo(team.getId());
        assertThat(saved.getRepoName()).isEqualTo(repository.getName());
        assertThat(saved.getPageSlug()).isEqualTo("overview");
        assertThat(saved.getTitle()).isEqualTo("System Overview");
        assertThat(saved.getContent()).isEqualTo(VALID_CONTENT);
        assertThat(saved.getOrderIndex()).isZero();
        assertThat(saved.getParentPage()).isNull();
        verify(wikiBatchLogger)
                .info(eq("GENERATE_WIKI"), contains("Created wiki page \"System Overview\" (slug=overview"));
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

        String result = writeWikiPageTool.writeWikiPage("overview", "New Title", VALID_CONTENT, null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' updated successfully.");
        assertThat(existing.getTitle()).isEqualTo("New Title");
        assertThat(existing.getContent()).isEqualTo(VALID_CONTENT);
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

        String result = writeWikiPageTool.writeWikiPage("overview", "Child", VALID_CONTENT, "parent", toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getParentPage()).isEqualTo(parent);
    }

    @Test
    void writeWikiPage_withBlankPageSlug_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("  ", "Title", "Content", null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("pageSlug must not be blank");
    }

    @Test
    void writeWikiPage_withBlankTitle_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "  ", "Content", null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("title must not be blank");
    }

    @Test
    void writeWikiPage_withBlankContent_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", "  ", null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("content must not be blank");
    }

    @Test
    void writeWikiPage_withHeadingOnlyContent_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage(
                        "authentication",
                        "Authentication & Security",
                        "# Authentication & Security\n",
                        null,
                        toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("at least 80 words of prose")
                .hasMessageContaining("this page has 0")
                .hasMessageContaining("Add a substantive body and retry");
        verify(ctxWikiPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void writeWikiPage_withInvalidMermaid_throwsToolException() {
        String content = WikiPageContentFixtures.WIKI_PAGE_BODY + """

                ```mermaid
                flowchart LR
                A --> B
                ```
                ```mermaid
                graph TD
                    A -> B
                ```
                """;

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("overview", "Title", content, null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("Invalid Mermaid diagram syntax in content")
                .hasMessageContaining("diagram #2, line 2");
        verify(ctxWikiPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void writeWikiPage_withCommonFlowchartForms_preservesSource() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        String content = WikiPageContentFixtures.WIKI_PAGE_BODY + """

                ```mermaid
                graph TD
                    subgraph External
                        API[API Layer] --> DB[(PostgreSQL)]
                    end
                ```
                """;

        String result = writeWikiPageTool.writeWikiPage("overview", "Title", content, null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo(content);
    }

    @Test
    void writeWikiPage_withMissingBatchId_throwsException() {
        ToolContext emptyContext = new ToolContext(Map.of());

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", VALID_CONTENT, null, emptyContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId not found");
    }

    @Test
    void writeWikiPage_withInvalidBatchId_throwsException() {
        ToolContext invalidContext = new ToolContext(Map.of("batchId", "not-a-uuid"));

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", VALID_CONTENT, null, invalidContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid batchId format");
    }

    @Test
    void writeWikiPage_withMissingBatch_throwsToolException() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", VALID_CONTENT, null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("Batch not found");
    }

    @Test
    void writeWikiPage_withMissingParent_throwsActionableToolExceptionWithAvailableSlugs() {
        CtxWikiPage existing = new CtxWikiPage();
        existing.setBatch(batch);
        existing.setPageSlug("overview");
        existing.setTitle("Overview");
        existing.setContent("content");

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "child")).thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "home")).thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchId(batchId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("child", "Child", VALID_CONTENT, "home", toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("'home'")
                .hasMessageContaining("overview")
                .hasMessageContaining("Fix and retry");
        verify(ctxWikiPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void writeWikiPage_withMissingParentAndNoPages_tellsModelToWriteRootFirst() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "child")).thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "home")).thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchId(batchId)).thenReturn(List.of());

        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("child", "Child", VALID_CONTENT, "home", toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("No wiki pages have been written yet")
                .hasMessageContaining("Fix and retry");
        verify(ctxWikiPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void writeWikiPage_normalizesHeaderFenceAndPersistsMermaidFence() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "arch")).thenReturn(Optional.empty());
        String input = WikiPageContentFixtures.WIKI_PAGE_BODY + """

                ```flowchart LR
                A["Client"] --> B["Server"]
                ```
                """;

        String result = writeWikiPageTool.writeWikiPage("arch", "Arch", input, null, toolContext);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'arch' created successfully.");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContent()).contains("```mermaid\nflowchart LR\n");
    }
}
