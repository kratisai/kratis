package com.kratisai.controlplane;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public class SlowTestTimingListener extends AbstractTestExecutionListener {

    private static final ThreadLocal<Long> methodStartTime = new ThreadLocal<>();

    @Override
    public void beforeTestMethod(TestContext testContext) {
        // By this point, the Spring Context is guaranteed to be loaded
        methodStartTime.set(System.currentTimeMillis());
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (testContext.getTestException() != null) {
            SlowTestRegistry.markFailed(testContext.getTestClass());
        }

        Long start = methodStartTime.get();
        if (start != null) {
            long duration = System.currentTimeMillis() - start;
            SlowTestRegistry.addTime(testContext.getTestClass(), duration);
            methodStartTime.remove();
        }
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();

        // Skip validation if any test in this class failed (timing is unreliable)
        if (SlowTestRegistry.hasFailed(testClass)) {
            return;
        }

        boolean hasSlowTest = testClass.isAnnotationPresent(SlowTest.class);
        boolean isContextPolluter = testClass.isAnnotationPresent(DirtiesContext.class);

        if (isContextPolluter && !hasSlowTest) {
            throw new RuntimeException("🔴 @SlowTest VALIDATION FAILED: " + testClass.getSimpleName()
                    + " forces a new Spring Context (e.g. @DirtiesContext) but is missing @SlowTest.");
        }
    }
}
