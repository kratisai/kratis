package com.kratisai.controlplane.testsupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * Auto-registered JUnit Jupiter extension that measures detailed per-class and per-method lifecycle
 * phase durations ({@code @BeforeAll}, Spring context/instance preparation, {@code @BeforeEach},
 * pure {@code @Test} execution, {@code @AfterEach}, {@code @AfterAll}) and feeds them into
 * {@link TestTimingRegistry}.
 */
public class TestExecutionTimingExtension
        implements BeforeAllCallback,
                AfterAllCallback,
                TestInstancePostProcessor,
                BeforeEachCallback,
                AfterEachCallback,
                BeforeTestExecutionCallback,
                AfterTestExecutionCallback {

    static {
        // Fallback shutdown hook in case the JVM aborts before testPlanExecutionFinished.
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> TestTimingRegistry.instance().emitReportsIfNotYetEmitted(), "test-timing-report"));
    }

    private static final Map<Class<?>, ClassState> CLASS_STATES = new ConcurrentHashMap<>();
    private final ThreadLocal<MethodState> methodState = new ThreadLocal<>();

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        Thread current = Thread.currentThread();
        long now = System.nanoTime();
        TestTimingRegistry.instance().recordClassStarted(testClass, current.getName(), now);
        CLASS_STATES.put(testClass, new ClassState(now));
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        long now = System.nanoTime();
        TestTimingRegistry.instance().markPrepareInstanceStarted(testClass, now);
        ClassState cs = CLASS_STATES.get(testClass);
        if (cs != null && cs.firstMethodPrepareStartNanos == 0L) {
            cs.firstMethodPrepareStartNanos = now;
        }
        MethodState ms = new MethodState();
        ms.prepareStartNanos = now;
        methodState.set(ms);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        long now = System.nanoTime();
        MethodState ms = methodState.get();
        if (ms == null) {
            ms = new MethodState();
            methodState.set(ms);
        } else if (ms.prepareStartNanos > 0L) {
            ms.prepareNanos = now - ms.prepareStartNanos;
        }
        ms.beforeEachStartNanos = now;
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        long now = System.nanoTime();
        MethodState ms = methodState.get();
        if (ms == null) {
            ms = new MethodState();
            methodState.set(ms);
        } else if (ms.beforeEachStartNanos > 0L) {
            ms.beforeEachNanos = now - ms.beforeEachStartNanos;
        }
        ms.testStartNanos = now;
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long now = System.nanoTime();
        MethodState ms = methodState.get();
        if (ms != null) {
            if (ms.testStartNanos > 0L) {
                ms.testNanos = now - ms.testStartNanos;
                boolean failed = context.getExecutionException().isPresent();
                TestTimingRegistry.instance()
                        .recordMethodExecution(
                                context.getRequiredTestClass(),
                                context.getRequiredTestMethod().getName(),
                                ms.testNanos,
                                failed);
            }
            ms.afterEachStartNanos = now;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        long now = System.nanoTime();
        Class<?> testClass = context.getRequiredTestClass();
        MethodState ms = methodState.get();
        methodState.remove();
        if (ms == null) {
            return;
        }
        long afterEachNanos = 0L;
        if (ms.afterEachStartNanos > 0L) {
            afterEachNanos = now - ms.afterEachStartNanos;
        } else if (ms.beforeEachStartNanos > 0L && ms.testStartNanos == 0L) {
            // @BeforeEach failed before beforeTestExecution ran
            ms.beforeEachNanos = now - ms.beforeEachStartNanos;
        }
        ClassState cs = CLASS_STATES.get(testClass);
        if (cs != null) {
            cs.lastMethodAfterEachEndNanos = now;
        }
        TestTimingRegistry.instance()
                .recordClassPhases(
                        testClass, 0L, ms.prepareNanos, ms.beforeEachNanos, ms.testNanos, afterEachNanos, 0L);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        long now = System.nanoTime();
        Class<?> testClass = context.getRequiredTestClass();
        ClassState cs = CLASS_STATES.remove(testClass);
        methodState.remove();
        if (cs != null) {
            long beforeAllNanos;
            long afterAllNanos;
            if (cs.firstMethodPrepareStartNanos > 0L) {
                beforeAllNanos = Math.max(0L, cs.firstMethodPrepareStartNanos - cs.beforeAllStartNanos);
                afterAllNanos =
                        cs.lastMethodAfterEachEndNanos > 0L ? Math.max(0L, now - cs.lastMethodAfterEachEndNanos) : 0L;
            } else {
                beforeAllNanos = Math.max(0L, now - cs.beforeAllStartNanos);
                afterAllNanos = 0L;
            }
            TestTimingRegistry.instance().recordClassPhases(testClass, beforeAllNanos, 0L, 0L, 0L, 0L, afterAllNanos);
        }
        boolean failed = context.getExecutionException().isPresent();
        TestTimingRegistry.instance().recordClassFinished(testClass, failed, now);
    }

    private static final class ClassState {
        private final long beforeAllStartNanos;
        private volatile long firstMethodPrepareStartNanos;
        private volatile long lastMethodAfterEachEndNanos;

        private ClassState(long beforeAllStartNanos) {
            this.beforeAllStartNanos = beforeAllStartNanos;
        }
    }

    private static final class MethodState {
        private long prepareStartNanos;
        private long prepareNanos;
        private long beforeEachStartNanos;
        private long beforeEachNanos;
        private long testStartNanos;
        private long testNanos;
        private long afterEachStartNanos;
    }
}
