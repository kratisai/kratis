package org.springframework.test.context.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kratisai.controlplane.InheritedTransactionContextCleanupListener;
import com.kratisai.controlplane.ResourcelessTransactionManager;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContext;
import org.springframework.transaction.support.DefaultTransactionDefinition;

class TestTransactionContextCleanerTest {

    @AfterEach
    void clearLeakedContext() {
        TestTransactionContextCleaner.clearInheritedTransactionContext();
    }

    @Test
    void clearInheritedTransactionContext_removesStaleContextAndReportsIt() {
        setStaleContext();

        assertThat(TestTransactionContextCleaner.clearInheritedTransactionContext())
                .isTrue();
        assertThat(TransactionContextHolder.getCurrentTransactionContext()).isNull();
        assertThat(TestTransactionContextCleaner.clearInheritedTransactionContext())
                .isFalse();
    }

    @Test
    void listener_runsBeforeTransactionalListenerAndClearsContextInheritedByChildThread() throws InterruptedException {
        setStaleContext();
        InheritedTransactionContextCleanupListener listener = new InheritedTransactionContextCleanupListener();
        AtomicReference<Boolean> inherited = new AtomicReference<>();
        AtomicReference<Boolean> cleared = new AtomicReference<>();

        Thread child = new Thread(() -> {
            inherited.set(TransactionContextHolder.getCurrentTransactionContext() != null);
            listener.beforeTestMethod(mock(TestContext.class));
            cleared.set(TransactionContextHolder.getCurrentTransactionContext() == null);
        });
        child.start();
        child.join();

        assertThat(listener.getOrder()).isLessThan(TransactionalTestExecutionListener.ORDER);
        assertThat(inherited.get()).isTrue();
        assertThat(cleared.get()).isTrue();
    }

    @Test
    void listener_clearsOnlyTheFirstTimeOnAThread() throws InterruptedException {
        InheritedTransactionContextCleanupListener listener = new InheritedTransactionContextCleanupListener();
        AtomicReference<Boolean> clearedOnFirstUse = new AtomicReference<>();
        AtomicReference<Boolean> keptOnLaterUse = new AtomicReference<>();

        Thread child = new Thread(() -> {
            setStaleContext();
            listener.beforeTestMethod(mock(TestContext.class));
            clearedOnFirstUse.set(TransactionContextHolder.getCurrentTransactionContext() == null);

            setStaleContext();
            listener.beforeTestMethod(mock(TestContext.class));
            keptOnLaterUse.set(TransactionContextHolder.getCurrentTransactionContext() != null);
            TestTransactionContextCleaner.clearInheritedTransactionContext();
        });
        child.start();
        child.join();

        assertThat(clearedOnFirstUse.get()).isTrue();
        assertThat(keptOnLaterUse.get()).isTrue();
    }

    private static void setStaleContext() {
        TransactionContextHolder.setCurrentTransactionContext(new TransactionContext(
                null, new ResourcelessTransactionManager(), new DefaultTransactionDefinition(), true));
    }
}
