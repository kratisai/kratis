package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class TrackingVirtualThreadExecutorTest {

    @Test
    void awaitIdle_waitsForInFlightTask() throws Exception {
        TrackingVirtualThreadExecutor executor = new TrackingVirtualThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                awaitQuietly(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.inFlight()).isEqualTo(1);

            release.countDown();
            executor.awaitIdle(Duration.ofSeconds(5));

            assertThat(executor.inFlight()).isZero();
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void awaitIdle_timesOutWhileTaskStillRunning() throws Exception {
        TrackingVirtualThreadExecutor executor = new TrackingVirtualThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                awaitQuietly(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.awaitIdle(Duration.ofMillis(100)))
                    .isInstanceOf(ConditionTimeoutException.class);
        } finally {
            release.countDown();
            executor.close();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
