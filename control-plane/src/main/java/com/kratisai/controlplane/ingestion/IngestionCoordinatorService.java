package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.api.wsdto.IngestionStatusEvent;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionCoordinatorService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionCoordinatorService.class);

    private final RepositoryRepository repositoryRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final IngestionWorker ingestionWorker;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final ApplicationEventPublisher eventPublisher;

    public IngestionCoordinatorService(
            RepositoryRepository repositoryRepository,
            IngestionBatchRepository ingestionBatchRepository,
            IngestionWorker ingestionWorker,
            LiteLLMProvisioningService litellmProvisioningService,
            ApplicationEventPublisher eventPublisher) {
        this.repositoryRepository = repositoryRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ingestionWorker = ingestionWorker;
        this.litellmProvisioningService = litellmProvisioningService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Triggers an ingestion by creating a batch with QUEUED status. The batch will be promoted to
     * PROCESSING when a worker thread becomes available.
     */
    @Transactional
    public IngestionBatch triggerIngestion(UUID repositoryId) {
        logger.info("Triggering ingestion for repository ID: {}", repositoryId);

        Repository repository = repositoryRepository
                .findById(repositoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));
        ensureTeamModelsRegistered(repository.getTeam());

        IngestionBatch batch = new IngestionBatch(repository);
        ingestionBatchRepository.saveAndFlush(batch);

        // Publish QUEUED event after this transaction commits
        eventPublisher.publishEvent(new IngestionStatusEvent(batch));

        // Submit to async worker AFTER this transaction commits to prevent race condition
        // where the worker tries to read the batch before it's persisted
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ingestionWorker.runIngestion(batch.getId());
            }
        });

        logger.info("Successfully queued ingestion batch {} for repository {}", batch.getId(), repository.getName());
        return batch;
    }

    private void ensureTeamModelsRegistered(Team team) {
        var chatProvider = team.getIngestionProvider();
        var chatModel = team.getIngestionModel();
        if (chatProvider == null || chatModel == null || chatModel.isBlank()) {
            throw new IllegalStateException(
                    "Team " + team.getId() + " has no ingestion provider/model. Models are required for ingestion.");
        }
        var embeddingProvider = team.getEmbeddingProvider();
        var embeddingModel = team.getEmbeddingModel();
        if (embeddingProvider == null || embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException("Team " + team.getId()
                    + " has no embedding provider/model. Embedding model is required for ingestion.");
        }
        litellmProvisioningService.ensureModelsRegistered(chatProvider, chatModel, embeddingProvider, embeddingModel);
    }
}
