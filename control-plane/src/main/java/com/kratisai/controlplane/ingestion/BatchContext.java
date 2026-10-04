package com.kratisai.controlplane.ingestion;

import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;

/** Extracts the ingestion batch id that every ingestion tool receives through its tool context. */
final class BatchContext {

    private BatchContext() {}

    static UUID requireBatchId(ToolContext toolContext, String toolName) {
        Object batchIdObj = toolContext != null ? toolContext.getContext().get("batchId") : null;
        if (batchIdObj == null) {
            throw new IllegalStateException(toolName + " ERROR: batchId not found in tool context.");
        }
        try {
            return batchIdObj instanceof UUID uuid ? uuid : UUID.fromString(batchIdObj.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(toolName + " ERROR: Invalid batchId format.");
        }
    }
}
