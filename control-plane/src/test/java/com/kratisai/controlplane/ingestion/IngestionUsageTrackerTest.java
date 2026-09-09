package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.MessageAggregator;

class IngestionUsageTrackerTest {

    private IngestionUsageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new IngestionUsageTracker();
    }

    @Test
    void recordTokens_accumulatesAcrossCalls() {
        UUID batchId = UUID.randomUUID();

        tracker.recordTokens(batchId, usage(100, 50, 150));
        tracker.recordTokens(batchId, usage(200, 100, 300));

        Optional<IngestionUsageTracker.Counters> counters = tracker.snapshotAndClear(batchId);

        assertThat(counters).isPresent();
        assertThat(counters.get().promptTokens()).isEqualTo(300);
        assertThat(counters.get().completionTokens()).isEqualTo(150);
        assertThat(counters.get().totalTokens()).isEqualTo(450);
        assertThat(counters.get().toolCalls()).isZero();
    }

    @Test
    void recordTokens_nullUsage_shouldBeIgnored() {
        UUID batchId = UUID.randomUUID();

        tracker.recordTokens(batchId, null);

        assertThat(tracker.snapshotAndClear(batchId)).isEmpty();
    }

    @Test
    void recordTokens_zeroValueUsage_shouldBeIgnored() {
        UUID batchId = UUID.randomUUID();

        tracker.recordTokens(batchId, usage(0, 0, 0));

        assertThat(tracker.snapshotAndClear(batchId)).isEmpty();
    }

    @Test
    void recordTokens_totalDerivedWhenTotalMissing() {
        UUID batchId = UUID.randomUUID();

        tracker.recordTokens(batchId, usage(100, 50, null));

        Optional<IngestionUsageTracker.Counters> counters = tracker.snapshotAndClear(batchId);

        assertThat(counters).isPresent();
        assertThat(counters.get().totalTokens()).isEqualTo(150);
    }

    @Test
    void recordToolCalls_accumulatesAcrossCalls() {
        UUID batchId = UUID.randomUUID();

        tracker.recordToolCalls(batchId, 3);
        tracker.recordToolCalls(batchId, 2);

        Optional<IngestionUsageTracker.Counters> counters = tracker.snapshotAndClear(batchId);

        assertThat(counters).isPresent();
        assertThat(counters.get().toolCalls()).isEqualTo(5);
        assertThat(counters.get().totalTokens()).isZero();
    }

    @Test
    void recordToolCalls_nonPositiveCount_shouldBeIgnored() {
        UUID batchId = UUID.randomUUID();

        tracker.recordToolCalls(batchId, 0);
        tracker.recordToolCalls(batchId, -1);

        assertThat(tracker.snapshotAndClear(batchId)).isEmpty();
    }

    @Test
    void snapshotAndClear_returnsEmptyWhenNothingRecorded() {
        UUID batchId = UUID.randomUUID();

        assertThat(tracker.snapshotAndClear(batchId)).isEmpty();
    }

    @Test
    void snapshotAndClear_clearsCountersForBatch() {
        UUID batchId = UUID.randomUUID();

        tracker.recordToolCalls(batchId, 2);
        tracker.snapshotAndClear(batchId);

        assertThat(tracker.snapshotAndClear(batchId)).isEmpty();
    }

    @Test
    void counters_areIsolatedBetweenBatches() {
        UUID batchA = UUID.randomUUID();
        UUID batchB = UUID.randomUUID();

        tracker.recordTokens(batchA, usage(10, 10, 20));
        tracker.recordToolCalls(batchB, 4);

        assertThat(tracker.snapshotAndClear(batchA))
                .get()
                .extracting(IngestionUsageTracker.Counters::totalTokens, IngestionUsageTracker.Counters::toolCalls)
                .containsExactly(20L, 0L);
        assertThat(tracker.snapshotAndClear(batchB))
                .get()
                .extracting(IngestionUsageTracker.Counters::totalTokens, IngestionUsageTracker.Counters::toolCalls)
                .containsExactly(0L, 4L);
    }

    @Test
    void recordTokens_isThreadSafe() throws Exception {
        UUID batchId = UUID.randomUUID();
        int threads = 8;
        int callsPerThread = 250;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        tracker.recordTokens(batchId, usage(1, 1, 2));
                        tracker.recordToolCalls(batchId, 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.set(new AssertionError("interrupted", e));
                }
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Optional<IngestionUsageTracker.Counters> counters = tracker.snapshotAndClear(batchId);
        assertThat(counters).isPresent();
        long expected = (long) threads * callsPerThread;
        assertThat(counters.get().promptTokens()).isEqualTo(expected);
        assertThat(counters.get().completionTokens()).isEqualTo(expected);
        assertThat(counters.get().totalTokens()).isEqualTo(2 * expected);
        assertThat(counters.get().toolCalls()).isEqualTo(expected);
        assertThat(failure.get()).isNull();
    }

    @Test
    void counters_rejectsNegativeValues() {
        assertThatThrownBy(() -> new IngestionUsageTracker.Counters(-1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionUsageTracker.Counters(0, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionUsageTracker.Counters(0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionUsageTracker.Counters(0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Usage usage(Integer prompt, Integer completion, Integer total) {
        return new MessageAggregator.DefaultUsage(prompt, completion, total);
    }
}
