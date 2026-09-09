package com.kratisai.controlplane;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Minimal in-memory {@code PlatformTransactionManager} for unit tests that need to exercise the
 * Spring transaction lifecycle (begin/commit/rollback and {@code afterCommit} synchronizations)
 * without a database. Spring removed its own {@code ResourcelessTransactionManager} in Framework 7.
 */
public class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
}
