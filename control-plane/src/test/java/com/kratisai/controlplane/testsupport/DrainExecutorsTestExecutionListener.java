package com.kratisai.controlplane.testsupport;

import com.kratisai.controlplane.service.SandboxExecutionUsageRefresher;
import java.time.Duration;
import java.util.ArrayList;
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

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);

    private static final List<String> EXECUTOR_BEAN_NAMES = List.of(
            "repoIngestTaskExecutor",
            "teamWikiTaskExecutor",
            "agentTaskExecutor",
            "envMessageExecutor",
            "clientBroadcastExecutor",
            "dispatchExecutor",
            "mermaidRepairExecutor");

    private static final List<String> SCHEDULER_BEAN_NAMES = List.of("usageRefreshScheduler", "wsCleanupScheduler");

    public static Set<String> trackedBeanNames() {
        return Stream.concat(EXECUTOR_BEAN_NAMES.stream(), SCHEDULER_BEAN_NAMES.stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void beforeTestMethod(@NonNull TestContext testContext) {
        drain(testContext, false);
    }

    @Override
    public void afterTestExecution(@NonNull TestContext testContext) {
        drain(testContext, true);
    }

    private static void drain(TestContext testContext, boolean afterTestExec) {
        long start = System.nanoTime();
        List<String> waitedBeans = new ArrayList<>();
        try {
            if (!testContext.hasApplicationContext()) {
                return;
            }
            ApplicationContext context = testContext.getApplicationContext();
            if (context.containsBean("sandboxExecutionUsageRefresher")) {
                context.getBean(SandboxExecutionUsageRefresher.class).cancelAllPendingRefreshes();
            }
            for (String beanName : EXECUTOR_BEAN_NAMES) {
                Object bean = beanOrNull(context, beanName);
                if (bean instanceof TrackingVirtualThreadExecutor tracked) {
                    if (tracked.inFlight() > 0) {
                        waitedBeans.add(beanName);
                    }
                    tracked.awaitIdle(DRAIN_TIMEOUT);
                } else if (bean instanceof ThreadPoolTaskExecutor executor) {
                    if (!isExecutorIdle(executor)) {
                        waitedBeans.add(beanName);
                    }
                    awaitIdle(executor);
                }
            }
            for (String beanName : SCHEDULER_BEAN_NAMES) {
                if (beanOrNull(context, beanName) instanceof TrackingTaskScheduler scheduler) {
                    if (scheduler.outstanding() > 0) {
                        waitedBeans.add(beanName);
                    }
                    scheduler.awaitIdle(DRAIN_TIMEOUT);
                }
            }
        } finally {
            TestTimingRegistry.instance().recordDrainExecutors(System.nanoTime() - start, afterTestExec, waitedBeans);
        }
    }

    private static Object beanOrNull(ApplicationContext context, String beanName) {
        return context.containsBean(beanName) ? context.getBean(beanName) : null;
    }

    private static boolean isExecutorIdle(ThreadPoolTaskExecutor executor) {
        var tpe = executor.getThreadPoolExecutor();
        return tpe.getActiveCount() == 0 && tpe.getQueue().isEmpty();
    }

    private static void awaitIdle(ThreadPoolTaskExecutor executor) {
        if (isExecutorIdle(executor)) {
            return;
        }
        Awaitility.await()
                .atMost(DRAIN_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .until(() -> isExecutorIdle(executor));
    }
}
