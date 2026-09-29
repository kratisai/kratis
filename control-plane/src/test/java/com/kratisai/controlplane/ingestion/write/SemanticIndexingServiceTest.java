package com.kratisai.controlplane.ingestion.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.service.EmbeddingModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

@ExtendWith(MockitoExtension.class)
class SemanticIndexingServiceTest {

    @Mock
    private CtxWikiPageRepository wikiPageRepository;

    @Mock
    private CtxEmbeddingRepository embeddingRepository;

    @Mock
    private EmbeddingModelFactory embeddingModelFactory;

    @Mock
    private LiteLLMProvisioningService litellmProvisioningService;

    private SemanticIndexingService semanticIndexingService;

    @Captor
    private ArgumentCaptor<List<CtxEmbedding>> embeddingsCaptor;

    private IngestionBatch batch;
    private Team team;

    @BeforeEach
    void setUp() {
        team = new Team("Test Team", "Test");
        ModelProvider embeddingProvider = new ModelProvider("OpenAI", ProviderType.OPENAI, "dummy", null);
        team.setEmbeddingProvider(embeddingProvider);
        team.setEmbeddingModel("text-embedding-3-small");
        batch = new IngestionBatch();

        EmbeddingModel embeddingModel = new EmbeddingModel() {
            @Override
            public float @NonNull [] embed(@NonNull Document document) {
                return new float[] {0.1f, 0.2f};
            }

            @Override
            public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request) {
                return new EmbeddingResponse(List.of(new Embedding(new float[] {0.1f, 0.2f}, 0)));
            }
        };

        Mockito.lenient()
                .when(embeddingModelFactory.createEmbeddingModelViaLiteLLM(any(), any(), any()))
                .thenReturn(embeddingModel);

        Mockito.lenient()
                .when(litellmProvisioningService.buildLiteLLMModelName(any(), any()))
                .thenAnswer(invocation -> {
                    String modelName = invocation.getArgument(1);
                    return "openai-test-provider-" + modelName.toLowerCase().replaceAll("[^a-z0-9-]", "-")
                            + "-12345678";
                });

        semanticIndexingService = new SemanticIndexingService(
                wikiPageRepository, embeddingRepository, embeddingModelFactory, litellmProvisioningService);
    }

    @Test
    void indexBatch_chunksAndSavesEmbeddings() {
        CtxWikiPage page = new CtxWikiPage();
        page.setPageSlug("overview");

        // Create content long enough to trigger chunking if needed,
        // but TokenTextSplitter defaults to larger chunk limits.
        page.setContent("This is a simple test document for wiki synthesis.");

        when(wikiPageRepository.findByBatchId(any())).thenReturn(List.of(page));

        semanticIndexingService.indexBatch(batch, team);

        verify(embeddingRepository).saveAll(embeddingsCaptor.capture());

        List<CtxEmbedding> saved = embeddingsCaptor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getChunkText()).contains("This is a simple test document");
        assertThat(saved.getFirst().getEmbedding()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void indexBatch_usesEmbeddingProviderAndModel_notIngestionProvider() {
        // Given: A team with both ingestion and embedding providers configured
        ModelProvider ingestionProvider =
                new ModelProvider("Ingestion Provider", ProviderType.OPENAI, "ingestion-key", null);
        ModelProvider embeddingProvider =
                new ModelProvider("Embedding Provider", ProviderType.OPENAI, "embedding-key", null);
        team.setIngestionProvider(ingestionProvider);
        team.setIngestionModel("gpt-4o");
        team.setEmbeddingProvider(embeddingProvider);
        team.setEmbeddingModel("text-embedding-3-small");

        CtxWikiPage page = new CtxWikiPage();
        page.setPageSlug("overview");
        page.setContent("Test content");

        when(wikiPageRepository.findByBatchId(any())).thenReturn(List.of(page));

        // When
        semanticIndexingService.indexBatch(batch, team);

        // Then: Verify that createEmbeddingModelViaLiteLLM was called with the embedding provider and model
        ArgumentCaptor<ModelProvider> providerCaptor = ArgumentCaptor.forClass(ModelProvider.class);
        ArgumentCaptor<String> modelNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingModelFactory)
                .createEmbeddingModelViaLiteLLM(providerCaptor.capture(), modelNameCaptor.capture(), any());

        ModelProvider capturedProvider = providerCaptor.getValue();
        String capturedModelName = modelNameCaptor.getValue();

        // Verify the embedding provider was used, not the ingestion provider
        assertThat(capturedProvider).isEqualTo(embeddingProvider);
        assertThat(capturedProvider.getApiKey()).isEqualTo("embedding-key");

        // Verify the embedding model name was used in the LiteLLM model name
        assertThat(capturedModelName).contains("text-embedding-3-small");
    }

    @Test
    void indexBatch_throwsExceptionWhenNoEmbeddingProviderConfigured() {
        // Given: A team with no embedding provider configured
        team.setEmbeddingProvider(null);
        team.setEmbeddingModel(null);

        CtxWikiPage page = new CtxWikiPage();
        page.setPageSlug("overview");
        page.setContent("Test content");

        when(wikiPageRepository.findByBatchId(any())).thenReturn(List.of(page));

        // When/Then: Verify that an exception is thrown
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> semanticIndexingService.indexBatch(batch, team))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding models are required for Semantic Indexing");

        // Verify that createEmbeddingModelViaLiteLLM was NOT called
        verify(embeddingModelFactory, org.mockito.Mockito.never()).createEmbeddingModelViaLiteLLM(any(), any(), any());
        verify(embeddingRepository, org.mockito.Mockito.never()).saveAll(any());
    }
}
