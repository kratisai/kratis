package com.kratisai.controlplane.ingestion;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * Aggregates LiteLLM token usage and tool-call counts across the ingestion pipeline for a given
 * batch.  Thread-safe: ingestion phases may record from concurrent virtual threads.
 */
@Component
public class IngestionUsageTracker {

    private final ConcurrentHashMap<UUID, MutableCounters> countersByBatch = new ConcurrentHashMap<>();

    @SuppressWarnings("ConstantValue")
    public void recordTokens(UUID batchId, Usage usage) {
        if (usage == null) {
            return;
        }
        Integer promptTokensValue = usage.getPromptTokens();
        int promptTokens = promptTokensValue != null ? promptTokensValue : 0;
        Integer completionTokensValue = usage.getCompletionTokens();
        int completionTokens = completionTokensValue != null ? completionTokensValue : 0;
        Integer totalTokensValue = usage.getTotalTokens();
        int totalTokens = totalTokensValue != null ? totalTokensValue : promptTokens + completionTokens;
        // Empty/default usage metadata (all zeros) carries no real token data.
        if (promptTokens == 0 && completionTokens == 0 && totalTokens == 0) {
            return;
        }

        countersByBatch.compute(batchId, (key, existing) -> {
            MutableCounters counters = existing != null ? existing : new MutableCounters();
            counters.promptTokens += promptTokens;
            counters.completionTokens += completionTokens;
            counters.totalTokens += totalTokens;
            return counters;
        });
    }

    public void recordToolCalls(UUID batchId, int toolCallCount) {
        if (toolCallCount <= 0) {
            return;
        }
        countersByBatch.compute(batchId, (key, existing) -> {
            MutableCounters counters = existing != null ? existing : new MutableCounters();
            counters.toolCalls += toolCallCount;
            return counters;
        });
    }

    /**
     * Returns the aggregated counters recorded so far for the batch and clears them.
     * Should be invoked once the ingestion pipeline has finished (or failed).
     */
    public Optional<Counters> snapshotAndClear(UUID batchId) {
        MutableCounters counters = countersByBatch.remove(batchId);
        return counters != null
                ? Optional.of(new Counters(
                        counters.promptTokens, counters.completionTokens, counters.totalTokens, counters.toolCalls))
                : Optional.empty();
    }

    public record Counters(long promptTokens, long completionTokens, long totalTokens, long toolCalls) {

        public Counters {
            if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || toolCalls < 0) {
                throw new IllegalArgumentException("Usage counters must not be negative");
            }
        }
    }

    private static final class MutableCounters {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long toolCalls;
    }
}
