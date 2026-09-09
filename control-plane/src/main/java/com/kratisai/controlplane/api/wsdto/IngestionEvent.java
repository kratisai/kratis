package com.kratisai.controlplane.api.wsdto;

import com.kratisai.controlplane.model.IngestionStatus;
import java.time.Instant;
import java.util.UUID;

/** Ingestion event types for real-time ingestion status broadcasting. */
public sealed interface IngestionEvent {

    /** An ingestion status update for a repository. */
    record StatusUpdate(UUID repositoryId, UUID batchId, IngestionStatus status, String commitHash, Instant completedAt)
            implements IngestionEvent {

        public StatusUpdate(UUID repositoryId, UUID batchId, IngestionStatus status) {
            this(repositoryId, batchId, status, null, null);
        }
    }
}
