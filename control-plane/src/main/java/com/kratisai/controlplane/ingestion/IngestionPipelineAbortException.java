package com.kratisai.controlplane.ingestion;

/**
 * Exception thrown to signal a hard-fail or abort condition in the ingestion pipeline.
 * This is typically used when a critical error occurs that cannot be recovered from
 * via standard retry mechanisms, such as exceeding maximum retries or encountering
 * an unrecoverable state during dimension research.
 */
public class IngestionPipelineAbortException extends RuntimeException {

    public IngestionPipelineAbortException(String message) {
        super(message);
    }

    public IngestionPipelineAbortException(String message, Throwable cause) {
        super(message, cause);
    }
}
