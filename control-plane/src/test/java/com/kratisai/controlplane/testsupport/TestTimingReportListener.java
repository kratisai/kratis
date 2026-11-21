package com.kratisai.controlplane.testsupport;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * JUnit Platform {@link TestExecutionListener} that anchors the suite wall-clock start/end,
 * tracks outermost class execution spans (including non-Spring and ArchUnit tests), and emits
 * the timing report cleanly before Surefire tears down stdout.
 */
public class TestTimingReportListener implements TestExecutionListener {

    private final Map<String, Long> nonJupiterLeafStarts = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> topClassesByUniqueId = new ConcurrentHashMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        TestTimingRegistry.instance().markSuiteStarted();
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        Optional<Class<?>> topClass = extractTopLevelClass(testIdentifier);
        if (topClass.isPresent()) {
            topClassesByUniqueId.put(testIdentifier.getUniqueId(), topClass.get());
            Thread current = Thread.currentThread();
            TestTimingRegistry.instance().recordClassStarted(topClass.get(), current.getName(), System.nanoTime());
            return;
        }
        if (testIdentifier.isTest() && !isJupiter(testIdentifier)) {
            nonJupiterLeafStarts.put(testIdentifier.getUniqueId(), System.nanoTime());
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        Optional<Class<?>> topClass = extractTopLevelClass(testIdentifier);
        if (topClass.isPresent()) {
            topClassesByUniqueId.remove(testIdentifier.getUniqueId());
            boolean failed = testExecutionResult.getStatus() != TestExecutionResult.Status.SUCCESSFUL;
            TestTimingRegistry.instance().recordClassFinished(topClass.get(), failed, System.nanoTime());
            return;
        }
        if (testIdentifier.isTest() && !isJupiter(testIdentifier)) {
            Long started = nonJupiterLeafStarts.remove(testIdentifier.getUniqueId());
            long durationNanos = started != null ? Math.max(0L, System.nanoTime() - started) : 0L;
            boolean failed = testExecutionResult.getStatus() != TestExecutionResult.Status.SUCCESSFUL;
            recordNonJupiterLeaf(testIdentifier, durationNanos, failed);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        TestTimingRegistry registry = TestTimingRegistry.instance();
        registry.markSuiteFinished();
        registry.emitReportsIfNotYetEmitted();
    }

    private static boolean isJupiter(TestIdentifier testIdentifier) {
        return testIdentifier.getUniqueId().contains("[engine:junit-jupiter]");
    }

    private void recordNonJupiterLeaf(TestIdentifier testIdentifier, long durationNanos, boolean failed) {
        TestSource source = testIdentifier.getSource().orElse(null);
        final Class<?> testClass;
        String methodOrRule = testIdentifier.getDisplayName();
        if (source instanceof MethodSource ms) {
            testClass = ms.getJavaClass();
            methodOrRule = ms.getMethodName();
        } else if (source instanceof ClassSource cs) {
            testClass = cs.getJavaClass();
        } else {
            String parentId = testIdentifier.getParentId().orElse("");
            Class<?> found = topClassesByUniqueId.get(parentId);
            if (found == null) {
                for (Map.Entry<String, Class<?>> entry : topClassesByUniqueId.entrySet()) {
                    if (testIdentifier.getUniqueId().startsWith(entry.getKey())) {
                        found = entry.getValue();
                        break;
                    }
                }
            }
            testClass = found;
        }
        if (testClass != null) {
            TestTimingRegistry.instance().recordClassPhases(testClass, 0L, 0L, 0L, durationNanos, 0L, 0L);
            TestTimingRegistry.instance().recordMethodExecution(testClass, methodOrRule, durationNanos, failed);
        }
    }

    private static Optional<Class<?>> extractTopLevelClass(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            return Optional.empty();
        }
        return testIdentifier
                .getSource()
                .filter(ClassSource.class::isInstance)
                .map(ClassSource.class::cast)
                .map(ClassSource::getJavaClass)
                .filter(cls -> !cls.isMemberClass());
    }
}
