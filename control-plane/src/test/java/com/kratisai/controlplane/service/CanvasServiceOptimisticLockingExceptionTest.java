package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanvasServiceOptimisticLockingExceptionTest {

    @Test
    void exception_preservesMessage() {
        CanvasService.OptimisticLockingException exception =
                new CanvasService.OptimisticLockingException("version conflict");

        assertThat(exception.getMessage()).isEqualTo("version conflict");
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}
