package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.config.TrackingTaskScheduler;
import com.kratisai.controlplane.config.TrackingVirtualThreadExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Drains shared async workers so lingering background work cannot leak across tests or race the
 * schema truncation. {@link #afterTestExecution} runs before {@code @AfterEach} (and therefore
 * before {@code databaseCleaner.cleanAll()}), preventing the truncate-versus-transaction
 * deadlock.
 */
public class DrainExecutorsTestExecutionListener implements TestExecutionListener {

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private static final List<String> EXECUTOR_BEAN_NAMES = List.of(
            "repoIngestTaskExecutor",
            "agentTaskExecutor",
            "envMessageExecutor",
            "clientBroadcastExecutor",
            "dispatchExecutor");

    private static final List<String> SCHEDULER_BEAN_NAMES = List.of("usageRefreshScheduler");

    static Set<String> trackedBeanNames() {
        return Stream.concat(EXECUTOR_BEAN_NAMES.stream(), SCHEDULER_BEAN_NAMES.stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void beforeTestMethod(@NonNull TestContext testContext) {
        drain(testContext);
    }

    @Override
    public void afterTestExecution(@NonNull TestContext testContext) {
        drain(testContext);
    }

    private static void drain(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        for (String beanName : EXECUTOR_BEAN_NAMES) {
            Object bean = beanOrNull(context, beanName);
            if (bean instanceof TrackingVirtualThreadExecutor tracked) {
                tracked.awaitIdle(DRAIN_TIMEOUT);
            } else if (bean instanceof ThreadPoolTaskExecutor executor) {
                awaitIdle(executor);
            }
        }
        for (String beanName : SCHEDULER_BEAN_NAMES) {
            if (beanOrNull(context, beanName) instanceof TrackingTaskScheduler scheduler) {
                scheduler.awaitIdle(DRAIN_TIMEOUT);
            }
        }
    }

    private static Object beanOrNull(ApplicationContext context, String beanName) {
        return context.containsBean(beanName) ? context.getBean(beanName) : null;
    }

    private static void awaitIdle(ThreadPoolTaskExecutor executor) {
        Awaitility.await()
                .atMost(DRAIN_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    var threadPoolExecutor = executor.getThreadPoolExecutor();
                    assertThat(threadPoolExecutor.getActiveCount()).isZero();
                    assertThat(threadPoolExecutor.getQueue()).isEmpty();
                });
    }
}
