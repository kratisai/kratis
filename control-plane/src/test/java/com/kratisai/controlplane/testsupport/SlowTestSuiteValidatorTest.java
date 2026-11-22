package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SlowTestSuiteValidatorTest {

    @Test
    void exemptsSlotGroupTestsFromLowerBoundWhileEnforcingDurationOnlySlowTests() {
        Map<Class<?>, AtomicLong> timingsWithSlotGroup = Map.of(
                FastSlotGroupTest.class, new AtomicLong(1_000L),
                NormalSlowTest.class, new AtomicLong(60_000L));

        assertThatCode(() -> SlowTestSuiteValidator.validateEntries(timingsWithSlotGroup, 50_000L, 20_000L))
                .doesNotThrowAnyException();

        Map<Class<?>, AtomicLong> timingsWithStalePlainSlowTest =
                Map.of(TooFastPlainSlowTest.class, new AtomicLong(1_000L));

        assertThatThrownBy(
                        () -> SlowTestSuiteValidator.validateEntries(timingsWithStalePlainSlowTest, 50_000L, 20_000L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("TooFastPlainSlowTest")
                .hasMessageContaining("MUST NOT be annotated with @SlowTest");

        Map<Class<?>, AtomicLong> timingsWithUnmarkedSlowTest = Map.of(UnmarkedSlowTest.class, new AtomicLong(60_000L));

        assertThatThrownBy(() -> SlowTestSuiteValidator.validateEntries(timingsWithUnmarkedSlowTest, 50_000L, 20_000L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("UnmarkedSlowTest")
                .hasMessageContaining("MUST be annotated with @SlowTest");
    }

    @SlowTest(slotGroup = "END_TO_END", maxConcurrency = 4)
    private static class FastSlotGroupTest {}

    @SlowTest
    private static class NormalSlowTest {}

    @SlowTest
    private static class TooFastPlainSlowTest {}

    private static class UnmarkedSlowTest {}
}
