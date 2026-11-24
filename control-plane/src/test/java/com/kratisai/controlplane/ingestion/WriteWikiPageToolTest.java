package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                new MermaidNormalizer(),
                new WikiPageContentValidator());
        batchId = UUID.randomUUID();
        wikiBatchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        toolContext = new ToolContext(Map.of("batchId", batchId, "wikiBatchLogger", wikiBatchLogger));
        lenient()
                .when(ctxWikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId))
                .thenReturn(List.of());
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

        String result = writePage("overview", "System Overview", VALID_CONTENT, null, null);

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
        assertThat(saved.getOrderIndex()).isEqualTo(10);
        assertThat(saved.getParentPage()).isNull();
        verify(wikiBatchLogger)
                .info(eq("GENERATE_WIKI"), contains("Created wiki page \"System Overview\" (slug=overview"));
    }

    @Test
    void writeWikiPage_withNullOrderIndex_appendsAfterTopLevelSiblings() {
        CtxWikiPage sibling = new CtxWikiPage();
        sibling.setId(UUID.randomUUID());
        sibling.setBatch(batch);
        sibling.setPageSlug("sibling");
        sibling.setOrderIndex(20);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId))
                .thenReturn(List.of(sibling));

        String result = writePage("overview", "System Overview", VALID_CONTENT, null, null);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(30);
    }

    @Test
    void writeWikiPage_withParentAndNullOrderIndex_appendsAfterParentSiblings() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setBatch(batch);
        parent.setPageSlug("parent");
        CtxWikiPage sibling = new CtxWikiPage();
        sibling.setId(UUID.randomUUID());
        sibling.setBatch(batch);
        sibling.setPageSlug("sibling");
        sibling.setParentPage(parent);
        sibling.setOrderIndex(20);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "parent")).thenReturn(Optional.of(parent));
        when(ctxWikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId))
                .thenReturn(List.of(parent, sibling));

        writePage("overview", "Child", VALID_CONTENT, "parent", null);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getParentPage()).isEqualTo(parent);
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(30);
    }

    @Test
    void writeWikiPage_updateWithoutParentSlug_keepsExistingParent() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setBatch(batch);
        parent.setPageSlug("home");
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("child");
        existing.setParentPage(parent);
        existing.setOrderIndex(4);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "child")).thenReturn(Optional.of(existing));

        writePage("child", "Child Updated", VALID_CONTENT, null, null);

        assertThat(existing.getParentPage()).isEqualTo(parent);
        assertThat(existing.getOrderIndex()).isEqualTo(4);
    }

    @Test
    void writeWikiPage_whitespaceParentSlug_isTreatedAsOmitted() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setBatch(batch);
        parent.setPageSlug("home");
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("child");
        existing.setParentPage(parent);
        existing.setOrderIndex(4);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "child")).thenReturn(Optional.of(existing));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "fresh")).thenReturn(Optional.empty());

        writePage("child", "Child", VALID_CONTENT, "   ", null);
        assertThat(existing.getParentPage()).isSameAs(parent);

        writePage("fresh", "Fresh", VALID_CONTENT, "   ", null);
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().getLast().getParentPage()).isNull();
    }

    @Test
    void writeWikiPage_updateWithParentSlug_reparentsPage() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setBatch(batch);
        parent.setPageSlug("home");
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("child");
        existing.setOrderIndex(4);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "child")).thenReturn(Optional.of(existing));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "home")).thenReturn(Optional.of(parent));

        writePage("child", "Child", VALID_CONTENT, "home", null);

        assertThat(existing.getParentPage()).isEqualTo(parent);
    }

    @Test
    void writeWikiPage_updateWithDescendantParent_throwsToolException() {
        CtxWikiPage parent = new CtxWikiPage();
        parent.setId(UUID.randomUUID());
        parent.setBatch(batch);
        parent.setPageSlug("home");
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("child");
        parent.setParentPage(existing);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "child")).thenReturn(Optional.of(existing));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "home")).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> writePage("child", "Child", VALID_CONTENT, "home", null))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("descendant of 'child'");
    }

    @Test
    void writeWikiPage_withSelfAsParent_throwsToolException() {
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("overview");

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> writePage("overview", "Overview", VALID_CONTENT, "overview", null))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("cannot be its own parent");
    }

    @Test
    void writeWikiPage_withExplicitOrderIndex_persistsIt() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.empty());

        writePage("overview", "System Overview", VALID_CONTENT, null, 15);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderIndex()).isEqualTo(15);
    }

    @Test
    void writeWikiPage_withExistingPage_updatesPage() {
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("overview");
        existing.setTitle("Old Title");
        existing.setContent("Old content");
        existing.setOrderIndex(7);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        String result = writePage("overview", "New Title", VALID_CONTENT, null, null);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' updated successfully.");
        assertThat(existing.getTitle()).isEqualTo("New Title");
        assertThat(existing.getContent()).isEqualTo(VALID_CONTENT);
        assertThat(existing.getOrderIndex()).isEqualTo(7);
        verify(ctxWikiPageRepository).saveAndFlush(existing);
    }

    @Test
    void writeWikiPage_withExistingPageAndExplicitOrderIndex_updatesOrderIndex() {
        CtxWikiPage existing = new CtxWikiPage();
        existing.setId(UUID.randomUUID());
        existing.setBatch(batch);
        existing.setPageSlug("overview");
        existing.setTitle("Old Title");
        existing.setContent("Old content");
        existing.setOrderIndex(7);

        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(existing));

        writePage("overview", "New Title", VALID_CONTENT, null, 42);

        assertThat(existing.getOrderIndex()).isEqualTo(42);
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

        String result = writePage("overview", "Child", VALID_CONTENT, "parent", null);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getParentPage()).isEqualTo(parent);
    }

    @Test
    void writeWikiPage_withBlankPageSlug_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("  ", "Title", "Content", null, null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("pageSlug must not be blank");
    }

    @Test
    void writeWikiPage_withBlankTitle_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "  ", "Content", null, null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("title must not be blank");
    }

    @Test
    void writeWikiPage_withBlankContent_throwsToolException() {
        assertThatThrownBy(() -> writeWikiPageTool.writeWikiPage("slug", "Title", "  ", null, null, toolContext))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("content must not be blank");
    }

    @Test
    void writeWikiPage_withHeadingOnlyContent_throwsToolException() {
        assertThatThrownBy(() -> writePage(
                        "authentication", "Authentication & Security", "# Authentication & Security\n", null, null))
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

        assertThatThrownBy(() -> writePage("overview", "Title", content, null, null))
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

        String result = writePage("overview", "Title", content, null, null);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'overview' created successfully.");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo(content);
    }

    @Test
    void writeWikiPage_withMissingBatchId_throwsException() {
        ToolContext emptyContext = new ToolContext(Map.of());

        assertThatThrownBy(
                        () -> writeWikiPageTool.writeWikiPage("slug", "Title", VALID_CONTENT, null, null, emptyContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId not found");
    }

    @Test
    void writeWikiPage_withInvalidBatchId_throwsException() {
        ToolContext invalidContext = new ToolContext(Map.of("batchId", "not-a-uuid"));

        assertThatThrownBy(() ->
                        writeWikiPageTool.writeWikiPage("slug", "Title", VALID_CONTENT, null, null, invalidContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid batchId format");
    }

    @Test
    void writeWikiPage_withMissingBatch_throwsToolException() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writePage("slug", "Title", VALID_CONTENT, null, null))
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

        assertThatThrownBy(() -> writePage("child", "Child", VALID_CONTENT, "home", null))
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

        assertThatThrownBy(() -> writePage("child", "Child", VALID_CONTENT, "home", null))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("No wiki pages have been written yet")
                .hasMessageContaining("Fix and retry");
        verify(ctxWikiPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void writeWikiPage_surgicallyFixesC4CommasAndPersistsFixedSource() {
        when(ingestionBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "arch")).thenReturn(Optional.empty());
        String input = WikiPageContentFixtures.WIKI_PAGE_BODY + """

                ```mermaid
                C4Context
                Person(player "Player")
                ```
                ```mermaid
                C4Container
                Container(spa "App" "Vite + React" "Application shell")
                ```
                """;

        writePage("arch", "Arch", input, null, null);

        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContent())
                .contains("Person(player, \"Player\")")
                .contains("Container(spa, \"App\", \"Vite + React\", \"Application shell\")");
    }

    private String writePage(String pageSlug, String title, String content, String parentPageSlug, Integer orderIndex) {
        return writeWikiPageTool.writeWikiPage(pageSlug, title, content, parentPageSlug, orderIndex, toolContext);
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

        String result = writePage("arch", "Arch", input, null, null);

        assertThat(result).isEqualTo("SUCCESS: Wiki page 'arch' created successfully.");
        ArgumentCaptor<CtxWikiPage> captor = ArgumentCaptor.forClass(CtxWikiPage.class);
        verify(ctxWikiPageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContent()).contains("```mermaid\nflowchart LR\n");
    }
}
