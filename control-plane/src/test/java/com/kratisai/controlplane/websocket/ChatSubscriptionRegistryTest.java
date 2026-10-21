package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.service.ChatSubscriptionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatSubscriptionRegistryTest {

    private ChatSubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChatSubscriptionRegistry();
    }

    @Test
    void subscribeChat_isIdempotentPerSession() {
        UUID chatId = UUID.randomUUID();

        assertThat(registry.subscribeChat("ws-1", chatId)).isTrue();
        assertThat(registry.subscribeChat("ws-1", chatId)).isFalse();
        assertThat(registry.isChatSubscribed("ws-1", chatId)).isTrue();
    }

    @Test
    void subscribeChat_isIndependentAcrossSessions() {
        UUID chatId = UUID.randomUUID();

        assertThat(registry.subscribeChat("ws-1", chatId)).isTrue();
        assertThat(registry.subscribeChat("ws-2", chatId)).isTrue();
    }

    @Test
    void unsubscribeChat_removesOnlyTheGivenSubscription() {
        UUID chatId = UUID.randomUUID();
        UUID otherChatId = UUID.randomUUID();
        registry.subscribeChat("ws-1", chatId);
        registry.subscribeChat("ws-1", otherChatId);

        assertThat(registry.unsubscribeChat("ws-1", chatId)).isTrue();
        assertThat(registry.isChatSubscribed("ws-1", chatId)).isFalse();
        assertThat(registry.isChatSubscribed("ws-1", otherChatId)).isTrue();
        assertThat(registry.unsubscribeChat("ws-1", chatId)).isFalse();
    }

    @Test
    void subscribeExecution_isIdempotentPerSession() {
        UUID executionId = UUID.randomUUID();

        assertThat(registry.subscribeExecution("ws-1", executionId)).isTrue();
        assertThat(registry.subscribeExecution("ws-1", executionId)).isFalse();
        assertThat(registry.isExecutionSubscribed("ws-1", executionId)).isTrue();
    }

    @Test
    void unsubscribeExecution_removesSubscription() {
        UUID executionId = UUID.randomUUID();
        registry.subscribeExecution("ws-1", executionId);

        assertThat(registry.unsubscribeExecution("ws-1", executionId)).isTrue();
        assertThat(registry.isExecutionSubscribed("ws-1", executionId)).isFalse();
        assertThat(registry.unsubscribeExecution("ws-1", executionId)).isFalse();
    }

    @Test
    void removeSession_dropsChatAndExecutionSubscriptions() {
        UUID chatId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        registry.subscribeChat("ws-1", chatId);
        registry.subscribeExecution("ws-1", executionId);
        registry.subscribeChat("ws-2", chatId);

        registry.removeSession("ws-1");

        assertThat(registry.isChatSubscribed("ws-1", chatId)).isFalse();
        assertThat(registry.isExecutionSubscribed("ws-1", executionId)).isFalse();
        assertThat(registry.isChatSubscribed("ws-2", chatId)).isTrue();
    }

    @Test
    void subscribeChat_isAtomicUnderConcurrency() throws Exception {
        UUID chatId = UUID.randomUUID();
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        if (registry.subscribeChat("ws-1", chatId)) {
                            added.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(added.get()).isEqualTo(1);
    }
}
