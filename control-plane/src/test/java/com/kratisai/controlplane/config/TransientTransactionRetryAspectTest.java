package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class TransientTransactionRetryAspectTest {

    private TransientTransactionRetryAspect aspect;
    private ProceedingJoinPoint joinPoint;

    @BeforeEach
    void setUp() {
        aspect = new TransientTransactionRetryAspect();
        joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("TestService.doSomething()");
        when(joinPoint.getSignature()).thenReturn(signature);
    }

    @Test
    void retryOnTransientDatabaseError_successFirstAttempt_returnsResult() throws Throwable {
        when(joinPoint.proceed()).thenReturn("success");

        Object result = aspect.retryOnTransientDatabaseError(joinPoint);

        assertThat(result).isEqualTo("success");
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void retryOnTransientDatabaseError_transientFailure_retriesAndSucceeds() throws Throwable {
        when(joinPoint.proceed())
                .thenThrow(new TransientDataAccessResourceException("Connection broken"))
                .thenReturn("recovered");

        Object result = aspect.retryOnTransientDatabaseError(joinPoint);

        assertThat(result).isEqualTo("recovered");
        verify(joinPoint, times(2)).proceed();
    }

    @Test
    void retryOnTransientDatabaseError_nonTransientError_failsImmediatelyWithoutRetry() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("Invalid state"));

        assertThatThrownBy(() -> aspect.retryOnTransientDatabaseError(joinPoint))
                .isInstanceOf(IllegalArgumentException.class);

        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void retryOnTransientDatabaseError_exhaustsMaxRetries_throwsLastException() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new TransientDataAccessResourceException("Persistent DB error"));

        assertThatThrownBy(() -> aspect.retryOnTransientDatabaseError(joinPoint))
                .isInstanceOf(TransientDataAccessResourceException.class);

        verify(joinPoint, times(3)).proceed();
    }

    @Test
    void retryOnTransientDatabaseError_interrupted_restoresInterruptAndThrows() throws Throwable {
        when(joinPoint.proceed()).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new TransientDataAccessResourceException("Failure with interrupt");
        });

        assertThatThrownBy(() -> aspect.retryOnTransientDatabaseError(joinPoint))
                .isInstanceOf(TransientDataAccessResourceException.class);

        assertThat(Thread.interrupted()).isTrue();
    }
}
