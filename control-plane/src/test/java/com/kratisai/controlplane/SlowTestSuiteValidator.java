package com.kratisai.controlplane;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.annotation.DirtiesContext;

public class SlowTestSuiteValidator implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SlowTestSuiteValidator.class);
    private static final double UPPER_THRESHOLD_PERCENT = 0.05; // 5% of total time
    private static final double LOWER_THRESHOLD_PERCENT = 0.02; // 2% of total time

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        Map<Class<?>, AtomicLong> allTimes = SlowTestRegistry.getAllTimes();
        if (allTimes.isEmpty()) {
            return;
        }

        // Check if we are running the full test suite
        boolean isFullSuite = !"false".equals(System.getProperty("kratis.test.full-suite"))
                && System.getProperty("test") == null
                && allTimes.size() >= 40;

        if (!isFullSuite) {
            log.info(
                    "📊 Skipping SlowTest validation (not running full suite: total classes run = {}, -Dtest = {}, full-suite prop = {})",
                    allTimes.size(),
                    System.getProperty("test"),
                    System.getProperty("kratis.test.full-suite"));
            return;
        }

        long totalPureExecutionMs =
                allTimes.values().stream().mapToLong(AtomicLong::get).sum();
        long upperThresholdMs = (long) (totalPureExecutionMs * UPPER_THRESHOLD_PERCENT);
        long lowerThresholdMs = (long) (totalPureExecutionMs * LOWER_THRESHOLD_PERCENT);

        // Sanity check caps so we don't force annotations for tiny suites
        upperThresholdMs = Math.max(upperThresholdMs, 5000);
        lowerThresholdMs = Math.max(lowerThresholdMs, 2000);

        log.info(
                "📊 SlowTest Validation Suite Stats: Total Pure Execution Time: {}ms, Upper Threshold (>{}%): {}ms, Lower Threshold (<{}%): {}ms",
                totalPureExecutionMs,
                UPPER_THRESHOLD_PERCENT * 100,
                upperThresholdMs,
                LOWER_THRESHOLD_PERCENT * 100,
                lowerThresholdMs);

        for (Map.Entry<Class<?>, AtomicLong> entry : allTimes.entrySet()) {
            Class<?> testClass = entry.getKey();

            if (SlowTestRegistry.hasFailed(testClass)) {
                continue;
            }

            long pureExecutionMs = entry.getValue().get();
            boolean hasSlowTest = testClass.isAnnotationPresent(SlowTest.class);
            boolean isContextPolluter = testClass.isAnnotationPresent(DirtiesContext.class);

            if (!hasSlowTest && pureExecutionMs >= upperThresholdMs) {
                throw new RuntimeException("🔴 @SlowTest VALIDATION FAILED: " + testClass.getSimpleName() + " took "
                        + pureExecutionMs + "ms (>= " + upperThresholdMs + "ms, " + (UPPER_THRESHOLD_PERCENT * 100)
                        + "% of total) and MUST be annotated with @SlowTest.");
            } else if (hasSlowTest && !isContextPolluter && pureExecutionMs < lowerThresholdMs) {
                throw new RuntimeException("🔴 @SlowTest VALIDATION FAILED: " + testClass.getSimpleName()
                        + " took only " + pureExecutionMs + "ms (< " + lowerThresholdMs + "ms, "
                        + (LOWER_THRESHOLD_PERCENT * 100) + "% of total) and MUST NOT be annotated with @SlowTest.");
            }
        }
    }
}
