package com.kratisai.controlplane;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Partitions Spring test classes into a fixed number of context slots.
 * <p>
 * Spring caches ApplicationContext instances based on this customizer's
 * equality. By computing a stable slot number from the test class name's
 * hash, Spring generates exactly #POOL_SIZE distinct contexts.
 * <p>
 * This guarantees isolated databases for parallel execution.
 */
public final class ThreadBoundContextCustomizer implements ContextCustomizer {

    private static final Logger log = LoggerFactory.getLogger(ThreadBoundContextCustomizer.class);

    // must match parallelism from junit-platform.properties
    public static final int POOL_SIZE = 4;
    private static final Map<Class<?>, Integer> slowTestSlots = new ConcurrentHashMap<>();
    private static final Map<String, Integer> groupSlots = new ConcurrentHashMap<>();
    private static final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private static final AtomicInteger groupSlotCounter = new AtomicInteger(POOL_SIZE);

    public static int getSlotForClass(Class<?> testClass) {
        SlowTest slowTest = testClass.getAnnotation(SlowTest.class);
        if (slowTest == null) {
            // & Integer.MAX_VALUE keeps the index non-negative for all hashCode() values
            // (Math.abs(Integer.MIN_VALUE) is negative).
            return (testClass.getName().hashCode() & Integer.MAX_VALUE) % POOL_SIZE;
        }

        String slotGroup = slowTest.slotGroup();
        if (slotGroup != null && !slotGroup.isEmpty()) {
            // Allocate a dedicated slot outside [0, POOL_SIZE-1] so normal tests never block on this group
            return groupSlots.computeIfAbsent(slotGroup, k -> groupSlotCounter.getAndIncrement());
        }

        return slowTestSlots.computeIfAbsent(testClass, k -> roundRobinCounter.getAndIncrement() % POOL_SIZE);
    }

    private final int slot;
    private final String testClassName;

    ThreadBoundContextCustomizer(int slot, String testClassName) {
        this.slot = slot;
        this.testClassName = testClassName;
    }

    static ThreadBoundContextCustomizer forClass(Class<?> testClass) {
        int slot = getSlotForClass(testClass);
        if (slot >= POOL_SIZE) {
            log.debug("📦 Test class '{}' assigned DEDICATED context slot {}", testClass.getSimpleName(), slot);
        } else {
            log.debug("📦 Test class '{}' assigned context slot {}/{}", testClass.getSimpleName(), slot, POOL_SIZE);
        }
        return new ThreadBoundContextCustomizer(slot, testClass.getName());
    }

    int getSlot() {
        return slot;
    }

    @Override
    public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ThreadBoundContextCustomizer other)) {
            return false;
        }
        return slot == other.slot;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(slot);
    }

    @Override
    public String toString() {
        return "ThreadBoundContextCustomizer[slot=" + slot + "/" + POOL_SIZE + ", class='" + testClassName + "']";
    }
}
