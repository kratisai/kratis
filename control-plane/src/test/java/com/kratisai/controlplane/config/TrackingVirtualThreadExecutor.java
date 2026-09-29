package com.kratisai.controlplane.config;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;

public class TrackingVirtualThreadExecutor implements Executor, AutoCloseable {

    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();

    @Override
    public void execute(@NonNull Runnable command) {
        inFlight.incrementAndGet();
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            throw e;
        }
    }

    public int inFlight() {
        return inFlight.get();
    }

    public void awaitIdle(Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(20)).until(() -> inFlight.get() == 0);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
