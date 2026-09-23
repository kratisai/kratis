package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackingTaskSchedulerTest {

    private TrackingTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TrackingTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-usage-refresh-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void awaitIdle_waitsForPendingScheduledTask() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        scheduler.schedule(ran::countDown, Instant.now().plusMillis(150));
        assertThat(scheduler.outstanding()).isEqualTo(1);

        scheduler.awaitIdle(Duration.ofSeconds(5));

        assertThat(ran.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.outstanding()).isZero();
    }

    @Test
    void awaitIdle_ignoresCancelledTask() {
        ScheduledFuture<?> future = scheduler.schedule(() -> {}, Instant.now().plusSeconds(30));
        assertThat(scheduler.outstanding()).isEqualTo(1);

        future.cancel(false);

        scheduler.awaitIdle(Duration.ofSeconds(2));
        assertThat(scheduler.outstanding()).isZero();
    }
}
