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

class IngestionUsageTrackerTest {

    private IngestionUsageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new IngestionUsageTracker();
    }

    @Test
    void recordToolCalls_accumulatesAcrossCalls() {
        UUID batchId = UUID.randomUUID();

        tracker.recordToolCalls(batchId, 3);
        tracker.recordToolCalls(batchId, 2);

        Optional<IngestionUsageTracker.Counters> counters = tracker.snapshotAndClear(batchId);

        assertThat(counters).isPresent();
        assertThat(counters.get().toolCalls()).isEqualTo(5);
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

        tracker.recordToolCalls(batchA, 3);
        tracker.recordToolCalls(batchB, 4);

        assertThat(tracker.snapshotAndClear(batchA))
                .get()
                .extracting(IngestionUsageTracker.Counters::toolCalls)
                .isEqualTo(3L);
        assertThat(tracker.snapshotAndClear(batchB))
                .get()
                .extracting(IngestionUsageTracker.Counters::toolCalls)
                .isEqualTo(4L);
    }

    @Test
    void recordToolCalls_isThreadSafe() throws Exception {
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
        assertThat(counters.get().toolCalls()).isEqualTo((long) threads * callsPerThread);
        assertThat(failure.get()).isNull();
    }

    @Test
    void counters_rejectsNegativeValues() {
        assertThatThrownBy(() -> new IngestionUsageTracker.Counters(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
