package com.kratisai.controlplane.ingestion;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Aggregates tool-call counts across the ingestion pipeline for a given batch. Token and spend
 * accounting comes exclusively from LiteLLM spend logs at finalize time. Thread-safe: ingestion
 * phases may record from concurrent virtual threads.
 */
@Component
public class IngestionUsageTracker {

    private final ConcurrentHashMap<UUID, AtomicLong> toolCallsByBatch = new ConcurrentHashMap<>();

    public void recordToolCalls(UUID batchId, int toolCallCount) {
        if (toolCallCount <= 0) {
            return;
        }
        toolCallsByBatch.compute(batchId, (key, existing) -> {
            AtomicLong counter = existing != null ? existing : new AtomicLong();
            counter.addAndGet(toolCallCount);
            return counter;
        });
    }

    /**
     * Returns the aggregated tool-call count recorded so far for the batch and clears it.
     * Should be invoked once the ingestion pipeline has finished (or failed).
     */
    public Optional<Counters> snapshotAndClear(UUID batchId) {
        AtomicLong counter = toolCallsByBatch.remove(batchId);
        return counter != null ? Optional.of(new Counters(counter.get())) : Optional.empty();
    }

    public record Counters(long toolCalls) {

        public Counters {
            if (toolCalls < 0) {
                throw new IllegalArgumentException("Usage counters must not be negative");
            }
        }
    }
}
