package com.kratisai.controlplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.transaction.TestTransactionContextCleaner;

/**
 * Removes a test transaction context inherited by a JUnit worker thread before Spring starts the
 * test's transaction.
 *
 * <p>See {@link TestTransactionContextCleaner} and
 * <a href="https://github.com/spring-projects/spring-framework/issues/33383">spring-framework#33383</a>
 * for the cause. Inheritance can only happen when the worker thread is created, so clearing once per
 * thread is enough; this also avoids masking a real leftover transaction from a buggy test.
 *
 * <p>Runs before {@code TransactionalTestExecutionListener} (order 4000) so the stale context is gone
 * before its "Cannot start new transaction without ending existing transaction" check.
 */
public class InheritedTransactionContextCleanupListener implements TestExecutionListener, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(InheritedTransactionContextCleanupListener.class);

    private static final int ORDER_BEFORE_TRANSACTIONAL = 3000;

    /** Listeners are instantiated per test class, but the guard is per JUnit worker thread. */
    private static final ThreadLocal<Boolean> checkedCurrentThread = ThreadLocal.withInitial(() -> false);

    @Override
    public int getOrder() {
        return ORDER_BEFORE_TRANSACTIONAL;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (Boolean.TRUE.equals(checkedCurrentThread.get())) {
            return;
        }
        checkedCurrentThread.set(true);
        if (TestTransactionContextCleaner.clearInheritedTransactionContext()) {
            logger.debug(
                    "Cleared stale transaction context inherited by thread {}",
                    Thread.currentThread().getName());
        }
    }
}
