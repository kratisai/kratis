package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchResolutionService {

    private static final Logger logger = LoggerFactory.getLogger(BatchResolutionService.class);

    private final RepositoryRepository repositoryRepository;
    private final IngestionBatchRepository batchRepository;

    public BatchResolutionService(RepositoryRepository repositoryRepository, IngestionBatchRepository batchRepository) {
        this.repositoryRepository = repositoryRepository;
        this.batchRepository = batchRepository;
    }

    @Transactional(readOnly = true)
    public IngestionBatch resolveActiveBatch(UUID teamId, String repoName) {
        Repository repo = resolveRepository(teamId, repoName);
        return batchRepository
                .findByRepositoryIdAndIsActiveTrue(repo.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("No active ingestion batch for repository '" + repoName + "'"));
    }

    @Transactional(readOnly = true)
    public Repository resolveRepository(UUID teamId, String repoName) {
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("Repository name must not be blank");
        }

        return repositoryRepository
                .findByTeamIdAndName(teamId, repoName)
                .orElseThrow(() ->
                        new IllegalArgumentException("Repository '" + repoName + "' not found for team " + teamId));
    }
}
