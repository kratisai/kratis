package com.kratisai.controlplane.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Aspect that wraps {@code @Transactional} methods and retries execution when transient database
 * connection errors occur. By running with {@link Ordered#HIGHEST_PRECEDENCE}, it executes outside
 * the {@code TransactionInterceptor}, allowing failed transactions to roll back before retrying on
 * a fresh connection.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransientTransactionRetryAspect {

    private static final Logger logger = LoggerFactory.getLogger(TransientTransactionRetryAspect.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 50;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) || "
            + "@within(org.springframework.transaction.annotation.Transactional)")
    public Object retryOnTransientDatabaseError(ProceedingJoinPoint joinPoint) throws Throwable {
        Throwable lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return joinPoint.proceed();
            } catch (Throwable ex) {
                lastException = ex;
                if (!TransientDatabaseExceptionClassifier.isTransient(ex) || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                logger.warn(
                        "Retrying transactional method {} (attempt {}/{}) due to transient DB failure: {}",
                        joinPoint.getSignature().toShortString(),
                        attempt,
                        MAX_ATTEMPTS,
                        ex.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw lastException != null ? lastException : new IllegalStateException("Retry loop exited without result");
    }
}
