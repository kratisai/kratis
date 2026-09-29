package com.kratisai.controlplane.api.wsdto;

import com.kratisai.controlplane.model.IngestionBatch;

/**
 * Domain event published when an ingestion batch changes status.
 *
 * <p>This event is designed to be consumed via {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} to ensure WebSocket notifications are only sent after the database transaction has
 * committed, preventing race conditions where clients refetch stale data.
 */
public record IngestionStatusEvent(IngestionBatch batch) {}
