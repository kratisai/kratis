package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.service.BatchResolutionService;
import com.kratisai.controlplane.service.EmbeddingModelFactory;
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
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
class WikiToolTest {

    @Mock
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Mock
    private CtxEmbeddingRepository ctxEmbeddingRepository;

    @Mock
    private BatchResolutionService batchResolutionService;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private EmbeddingModelFactory embeddingModelFactory;

    @Mock
    private EmbeddingModel embeddingModel;

    private WikiTool wikiTool;

    private UUID teamId;
    private UUID batchId;
    private ToolContext toolContext;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        wikiTool = new WikiTool(
                ctxWikiPageRepository,
                ctxEmbeddingRepository,
                batchResolutionService,
                teamRepository,
                embeddingModelFactory);

        teamId = UUID.randomUUID();
        batchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of("teamId", teamId, "chatId", UUID.randomUUID()));

        batch = new IngestionBatch();
        batch.setId(batchId);
    }

    @Test
    void listWikiPages_returnsHierarchy() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxWikiPage rootPage = createWikiPage("overview", "Overview", null, "Root content");
        CtxWikiPage childPage = createWikiPage("architecture", "Architecture", rootPage, "Child content");

        when(ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                .thenReturn(List.of(rootPage));
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, rootPage.getId()))
                .thenReturn(List.of(childPage));
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, childPage.getId()))
                .thenReturn(List.of());

        List<WikiTool.WikiPageSummary> result = wikiTool.listWikiPages("test-repo", toolContext);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().pageSlug()).isEqualTo("overview");
        assertThat(result.getFirst().title()).isEqualTo("Overview");
        assertThat(result.getFirst().depth()).isEqualTo(0);
        assertThat(result.getFirst().contentLength()).isEqualTo("Root content".length());

        assertThat(result.get(1).pageSlug()).isEqualTo("architecture");
        assertThat(result.get(1).title()).isEqualTo("Architecture");
        assertThat(result.get(1).depth()).isEqualTo(1);
        assertThat(result.get(1).contentLength()).isEqualTo("Child content".length());
    }

    @Test
    void listWikiPages_withNullContent_returnsZeroLength() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxWikiPage page = createWikiPage("empty", "Empty Page", null, null);

        when(ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                .thenReturn(List.of(page));
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIdOrderByOrderIndexAsc(batchId, page.getId()))
                .thenReturn(List.of());

        List<WikiTool.WikiPageSummary> result = wikiTool.listWikiPages("test-repo", toolContext);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().contentLength()).isEqualTo(0);
    }

    @Test
    void listWikiPages_withNoPages_returnsEmptyList() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        when(ctxWikiPageRepository.findByBatchIdAndParentPageIsNullOrderByOrderIndexAsc(batchId))
                .thenReturn(List.of());

        List<WikiTool.WikiPageSummary> result = wikiTool.listWikiPages("test-repo", toolContext);

        assertThat(result).isEmpty();
    }

    @Test
    void readWikiPage_returnsFormattedContent() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxWikiPage page =
                createWikiPage("overview", "System Overview", null, "# Introduction\n\nThis is the overview.");

        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "overview"))
                .thenReturn(Optional.of(page));

        String result = wikiTool.readWikiPage("test-repo", "overview", toolContext);

        assertThat(result).isEqualTo("# System Overview\n\n# Introduction\n\nThis is the overview.");
    }

    @Test
    void readWikiPage_withNonExistentPage_throwsException() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        when(ctxWikiPageRepository.findByBatchIdAndPageSlug(batchId, "nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> wikiTool.readWikiPage("test-repo", "nonexistent", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wiki page 'nonexistent' not found");
    }

    @Test
    void searchWiki_usesEmbeddingProviderAndModel() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        Team team = new Team();
        team.setId(teamId);
        ModelProvider ingestionProvider = new ModelProvider();
        ingestionProvider.setId(UUID.randomUUID());
        team.setIngestionProvider(ingestionProvider);
        team.setIngestionModel("gpt-4o");
        ModelProvider embeddingProvider = new ModelProvider();
        embeddingProvider.setId(UUID.randomUUID());
        team.setEmbeddingProvider(embeddingProvider);
        team.setEmbeddingModel("text-embedding-3-small");

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(embeddingModelFactory.createEmbeddingModel(embeddingProvider, "text-embedding-3-small"))
                .thenReturn(embeddingModel);

        float[] queryVector = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed("authentication")).thenReturn(queryVector);

        CtxWikiPage page1 = createWikiPage("auth-overview", "Authentication Overview", null, "Auth content");
        CtxWikiPage page2 = createWikiPage("security", "Security Guide", null, "Security content");

        CtxEmbedding embedding1 = createEmbedding(page1, "Authentication is handled by...");
        CtxEmbedding embedding2 = createEmbedding(page2, "Security best practices include...");

        when(ctxEmbeddingRepository.findSimilar(teamId, batchId, queryVector, 3, 5))
                .thenReturn(List.of(embedding1, embedding2));

        WikiTool.WikiSearchResponse result = wikiTool.searchWiki("test-repo", "authentication", toolContext);

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().getFirst().pageSlug()).isEqualTo("auth-overview");
        assertThat(result.results().getFirst().pageTitle()).isEqualTo("Authentication Overview");
        assertThat(result.results().getFirst().matchedText()).isEqualTo("Authentication is handled by...");

        assertThat(result.results().get(1).pageSlug()).isEqualTo("security");
        assertThat(result.results().get(1).pageTitle()).isEqualTo("Security Guide");
        assertThat(result.results().get(1).matchedText()).isEqualTo("Security best practices include...");
    }

    @Test
    void searchWiki_withNoResults_returnsEmptyList() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        Team team = new Team();
        team.setId(teamId);
        ModelProvider provider = new ModelProvider();
        provider.setId(UUID.randomUUID());
        team.setEmbeddingProvider(provider);
        team.setEmbeddingModel("text-embedding-3-small");

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(embeddingModelFactory.createEmbeddingModel(provider, "text-embedding-3-small"))
                .thenReturn(embeddingModel);

        float[] queryVector = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed("nonexistent topic")).thenReturn(queryVector);
        when(ctxEmbeddingRepository.findSimilar(teamId, batchId, queryVector, 3, 5))
                .thenReturn(List.of());

        WikiTool.WikiSearchResponse result = wikiTool.searchWiki("test-repo", "nonexistent topic", toolContext);

        assertThat(result.results()).isEmpty();
    }

    @Test
    void searchWiki_withNoEmbeddingProvider_throwsException() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        Team team = new Team();
        team.setId(teamId);
        team.setEmbeddingProvider(null);

        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> wikiTool.searchWiki("test-repo", "query", toolContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding provider");
    }

    @Test
    void searchWiki_withNonExistentTeam_throwsException() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        when(teamRepository.findById(teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wikiTool.searchWiki("test-repo", "query", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Team not found");
    }

    private CtxWikiPage createWikiPage(String slug, String title, CtxWikiPage parent, String content) {
        CtxWikiPage page = new CtxWikiPage();
        page.setId(UUID.randomUUID());
        page.setPageSlug(slug);
        page.setTitle(title);
        page.setParentPage(parent);
        page.setContent(content);
        return page;
    }

    private CtxEmbedding createEmbedding(CtxWikiPage page, String chunkText) {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        return new CtxEmbedding(batch, null, page, chunkText, embedding);
    }
}
