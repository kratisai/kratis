package com.kratisai.controlplane.testsupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Spring {@link TestExecutionListener} registered via {@code META-INF/spring.factories} that tracks
 * Spring {@code ApplicationContext} cache hits, misses, and context-to-class associations.
 *
 * <p>Runs after {@code DependencyInjectionTestExecutionListener} (order 2000) so the test's
 * {@link ApplicationContext} is already resolved during {@link #prepareTestInstance(TestContext)}.
 */
public class ContextCacheTracker implements TestExecutionListener, Ordered {

    private static final int ORDER_AFTER_DI = 2500;

    private final Map<Class<?>, Boolean> hitBeforeClass = new ConcurrentHashMap<>();
    private final Map<Class<?>, Long> classEntryNanos = new ConcurrentHashMap<>();

    @Override
    public int getOrder() {
        return ORDER_AFTER_DI;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        hitBeforeClass.put(testClass, testContext.hasApplicationContext());
        classEntryNanos.put(testClass, System.nanoTime());
    }

    @Override
    public void prepareTestInstance(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        if (!testContext.hasApplicationContext()) {
            return;
        }
        ApplicationContext ctx = testContext.getApplicationContext();
        if (ctx instanceof ConfigurableApplicationContext configurableCtx) {
            boolean wasCachedBeforeClass = hitBeforeClass.getOrDefault(testClass, false);
            long start = classEntryNanos.getOrDefault(testClass, System.nanoTime());
            long elapsed = Math.max(0L, System.nanoTime() - start);
            TestTimingRegistry.instance()
                    .recordClassContextUsage(testClass, configurableCtx, wasCachedBeforeClass, elapsed);
        }
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        hitBeforeClass.remove(testClass);
        classEntryNanos.remove(testClass);
    }
}
