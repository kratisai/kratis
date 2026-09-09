package com.kratisai.controlplane.ingestion.write;

import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.service.EmbeddingModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemanticIndexingService {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexingService.class);

    // Chunk size guidelines: ~500-1000 tokens. TokenTextSplitter defaults to 800
    // chunks.
    private final TokenTextSplitter textSplitter = TokenTextSplitter.builder().build();

    private final CtxWikiPageRepository wikiPageRepository;
    private final CtxEmbeddingRepository embeddingRepository;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;

    public SemanticIndexingService(
            CtxWikiPageRepository wikiPageRepository,
            CtxEmbeddingRepository embeddingRepository,
            EmbeddingModelFactory embeddingModelFactory,
            LiteLLMProvisioningService litellmProvisioningService) {
        this.wikiPageRepository = wikiPageRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingModelFactory = embeddingModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
    }

    @Transactional
    public void indexBatch(IngestionBatch batch, Team team) {
        log.info("Starting semantic indexing for batch: {}", batch.getId());
        List<CtxWikiPage> pages = wikiPageRepository.findByBatchId(batch.getId());

        ModelProvider provider = team.getEmbeddingProvider();
        String embeddingModelName = team.getEmbeddingModel();
        if (provider == null || embeddingModelName == null) {
            throw new IllegalStateException("Embedding models are required for Semantic Indexing.");
        }

        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(provider, embeddingModelName);
        String virtualKey = batch.getUsage().getVirtualKey();
        EmbeddingModel embeddingModel =
                embeddingModelFactory.createEmbeddingModelViaLiteLLM(provider, litellmModelName, virtualKey);

        List<CtxEmbedding> embeddingsToSave = new ArrayList<>();

        for (CtxWikiPage page : pages) {
            log.debug("Chunking page: {}", page.getPageSlug());
            List<Document> chunks = textSplitter.apply(List.of(new Document(page.getContent())));

            for (Document docChunk : chunks) {
                String chunk = docChunk.getText();
                float[] vector = embeddingModel.embed(chunk);
                embeddingsToSave.add(new CtxEmbedding(batch, team, page, chunk, vector));
            }
        }

        embeddingRepository.saveAll(embeddingsToSave);
        log.info("Completed semantic indexing for batch: {}. Saved {} chunks.", batch.getId(), embeddingsToSave.size());
    }
}
