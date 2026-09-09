package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Drains the shared asynchronous worker executors before each test method so
 * that
 * lingering async work from a previous test cannot leak across test boundaries.
 */
public class DrainExecutorsTestExecutionListener implements TestExecutionListener {

    private static final List<String> EXECUTOR_BEAN_NAMES = List.of("repoIngestTaskExecutor", "agentTaskExecutor");

    @Override
    public void beforeTestMethod(@NonNull TestContext testContext) {
        for (String beanName : EXECUTOR_BEAN_NAMES) {
            try {
                ThreadPoolTaskExecutor executor =
                        testContext.getApplicationContext().getBean(beanName, ThreadPoolTaskExecutor.class);
                awaitIdle(executor);
            } catch (Exception e) {
                // Bean may not be present in contexts that do not declare it; ignore.
            }
        }
    }

    private static void awaitIdle(ThreadPoolTaskExecutor executor) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    var threadPoolExecutor = executor.getThreadPoolExecutor();
                    assertThat(threadPoolExecutor.getActiveCount()).isZero();
                    assertThat(threadPoolExecutor.getQueue()).isEmpty();
                });
    }
}
