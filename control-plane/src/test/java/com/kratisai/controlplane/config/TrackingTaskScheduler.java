package com.kratisai.controlplane.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Periodic {@code @Scheduled} tasks never complete, so only one-shot tasks are tracked. */
public class TrackingTaskScheduler extends ThreadPoolTaskScheduler {

    private final Set<ScheduledFuture<?>> outstanding = ConcurrentHashMap.newKeySet();

    @Override
    public @NonNull ScheduledFuture<?> schedule(@NonNull Runnable task, @NonNull Instant startTime) {
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        ScheduledFuture<?> future = super.schedule(
                () -> {
                    try {
                        task.run();
                    } finally {
                        outstanding.remove(holder[0]);
                    }
                },
                startTime);
        holder[0] = future;
        outstanding.add(future);
        return future;
    }

    public int outstanding() {
        prune();
        return outstanding.size();
    }

    public void awaitIdle(Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(20)).until(() -> {
            prune();
            return outstanding.isEmpty();
        });
    }

    private void prune() {
        outstanding.removeIf(ScheduledFuture::isDone);
    }
}
