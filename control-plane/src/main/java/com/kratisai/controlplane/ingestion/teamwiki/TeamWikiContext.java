package com.kratisai.controlplane.ingestion.teamwiki;

import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;

/** Extracts TeamWikiBatch execution context from Spring AI's {@link ToolContext}. */
final class TeamWikiContext {

    static final String BATCH_ID_KEY = "teamWikiBatchId";
    static final String REPO_BATCHES_KEY = "repoBatchIdsByName";
    static final String LOGGER_KEY = "teamWikiBatchLogger";

    private TeamWikiContext() {}

    static UUID requireBatchId(ToolContext toolContext, String caller) {
        if (toolContext == null) {
            throw new IllegalStateException(caller + " requires ToolContext with '" + BATCH_ID_KEY + "'");
        }
        Object raw = toolContext.getContext().get(BATCH_ID_KEY);
        if (raw == null) {
            throw new IllegalStateException(caller + " requires '" + BATCH_ID_KEY + "' in ToolContext");
        }
        return raw instanceof UUID uuid ? uuid : UUID.fromString(raw.toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, UUID> repoBatchIdsByName(ToolContext toolContext) {
        if (toolContext == null) {
            return Map.of();
        }
        Object raw = toolContext.getContext().get(REPO_BATCHES_KEY);
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, UUID>) map;
        }
        return Map.of();
    }
}
