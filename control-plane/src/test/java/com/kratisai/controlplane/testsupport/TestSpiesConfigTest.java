package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestSpiesConfigTest {

    @Test
    void delegatingSpySupportsStubbingVerificationAndRetainsDelegationAfterReset() {
        SampleService real = new SampleService();
        SampleService spy = TestSpiesConfig.wrapAsDelegatingSpy(SampleService.class, real);

        assertThat(spy.greet("initial")).isEqualTo("hello initial");

        doReturn("stubbed").when(spy).greet("world");
        assertThat(spy.greet("world")).isEqualTo("stubbed");
        verify(spy).greet("world");

        Mockito.reset(spy);
        assertThat(spy.greet("kratis")).isEqualTo("hello kratis");
        assertThat(real.calls).isEqualTo(2);
    }

    static class SampleService {
        private int calls;

        String greet(String name) {
            calls++;
            return "hello " + name;
        }
    }
}
