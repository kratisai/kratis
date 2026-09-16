package org.springframework.test.context.transaction;

/**
 * Clears the Spring Test transaction context bound to the current thread.
 *
 * <p>{@code TransactionContextHolder} stores its context in an {@link java.lang.InheritableThreadLocal}.
 * When JUnit runs test classes concurrently it uses a {@code ForkJoinPool}; if a worker blocks in
 * {@code ForkJoinPool.managedBlock} (JUnit does this while acquiring resource locks) the pool may add
 * a compensating worker, which inherits the blocking worker's in-flight transaction context. The next
 * transactional test picked up by that thread then fails Spring's pre-test check with
 * {@code IllegalStateException: Cannot start new transaction without ending existing transaction}.
 *
 * <p>Spring has no public API for this and considers the diagnosis unreliable, so the cleanup lives
 * in {@code TransactionContextHolder}'s package to reach the package-private holder. Removing the
 * inherited reference does not end the originating thread's transaction.
 *
 * @see <a href="https://github.com/spring-projects/spring-framework/issues/33383">spring-framework#33383</a>
 */
public final class TestTransactionContextCleaner {

    private TestTransactionContextCleaner() {}

    /**
     * Drop the transaction context inherited by the current thread, if any.
     *
     * @return {@code true} if a stale context was removed
     */
    public static boolean clearInheritedTransactionContext() {
        return TransactionContextHolder.removeCurrentTransactionContext() != null;
    }
}
