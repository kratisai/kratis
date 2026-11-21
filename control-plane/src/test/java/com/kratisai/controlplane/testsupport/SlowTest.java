package com.kratisai.controlplane.testsupport;

import java.lang.annotation.*;
import org.junit.jupiter.api.Tag;

/**
 * Annotation used to mark tests that are exceptionally slow or that pollute the Spring Context
 * (e.g., using @MockBean, @DirtiesContext).
 *
 * <p>Applying this annotation does two things:
 * 1. Tags the test with JUnit's "@Tag("slow")", allowing it to be excluded via Maven/Surefire.
 * 2. Signals the ThreadBoundContextCustomizer to load-balance this test into a deterministic
 *    round-robin slot, preventing long-running tests from stacking into the same ForkJoin thread.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag("slow")
public @interface SlowTest {
    /**
     * Optional label to group slow tests into the same execution slot.
     * Tests with the same slotGroup share the allocated slot(s) and Spring Context(s).
     */
    String slotGroup() default "";

    /**
     * Maximum number of concurrent execution slots allocated to {@link #slotGroup()}.
     * Defaults to {@code 1} (strictly serialized on a single thread). Values {@code > 1}
     * partition the group across dedicated lanes in round-robin order.
     */
    int maxConcurrency() default 1;
}
