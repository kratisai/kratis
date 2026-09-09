package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.IngestionPipelineAbortException;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DimensionResearchService {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(DimensionResearchService.class);

    private final CtxDimensionRepository ctxDimensionRepository;
    private final ResearchDimensionWorker researchDimensionWorker;
    private final IngestionProperties ingestionProperties;
    private final IngestionBatchLogService ingestionBatchLogService;

    public DimensionResearchService(
            CtxDimensionRepository ctxDimensionRepository,
            ResearchDimensionWorker researchDimensionWorker,
            IngestionProperties ingestionProperties,
            IngestionBatchLogService ingestionBatchLogService) {
        this.ctxDimensionRepository = ctxDimensionRepository;
        this.researchDimensionWorker = researchDimensionWorker;
        this.ingestionProperties = ingestionProperties;
        this.ingestionBatchLogService = ingestionBatchLogService;
    }

    public void researchDimensions(IngestionBatch batch) {
        var batchLogger = new IngestionBatchLogService.BatchLogger(
                batch.getId(), batch.getRepository().getTeam().getId(), ingestionBatchLogService);

        List<CtxDimension> dimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        if (dimensions.isEmpty()) {
            throw new IllegalStateException("No dimensions found for batch.");
        }

        var team = batch.getRepository().getTeam();
        var provider = team.getIngestionProvider();
        // Force-initialize the (potentially lazy) ModelProvider before passing to child-threads.
        Hibernate.initialize(provider);
        var modelName = team.getIngestionModel();
        var batchId = batch.getId();

        Semaphore researchConcurrency =
                new Semaphore(ingestionProperties.getDimensionResearch().getMaxConcurrency());
        List<Future<Map.Entry<CtxDimension, DimensionSynopsisResult>>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (CtxDimension dimension : dimensions) {
                ResearchDimensionTask task = new ResearchDimensionTask(batchId, provider, modelName, dimension);
                futures.add(executor.submit(() -> {
                    researchConcurrency.acquire();
                    try {
                        DimensionSynopsisResult result = researchDimensionWorker.research(task, batchLogger, batch);
                        return Map.entry(dimension, result);
                    } finally {
                        researchConcurrency.release();
                    }
                }));
            }

            for (Future<Map.Entry<CtxDimension, DimensionSynopsisResult>> future : futures) {
                try {
                    Map.Entry<CtxDimension, DimensionSynopsisResult> entry = future.get();
                    CtxDimension dimension = entry.getKey();
                    DimensionSynopsisResult result = entry.getValue();

                    if (result.getSynopsis() != null && !result.getSynopsis().isBlank()) {
                        dimension.setSynopsis(result.getSynopsis());
                        ctxDimensionRepository.save(dimension);
                    } else {
                        batchLogger.error(
                                "RESEARCH_DIMENSIONS",
                                "Failed to generate synopsis for dimension: " + dimension.getName());
                        // TODO: Abort the ingestion pipeline if dimension research fails.
                    }
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IngestionPipelineAbortException abortException) {
                        throw abortException;
                    }
                    throw new RuntimeException("Failed to research dimension", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted while awaiting dimension research", e);
                }
            }

            // Completed successfully
        }
    }
}
