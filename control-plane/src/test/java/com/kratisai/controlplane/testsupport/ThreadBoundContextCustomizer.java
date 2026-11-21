package com.kratisai.controlplane.testsupport;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Partitions Spring test classes into a fixed number of context slots.
 * <p>
 * Spring caches ApplicationContext instances based on this customizer's
 * equality. By computing a stable slot number from the test class name's
 * hash, Spring generates exactly #POOL_SIZE distinct contexts for fast tests,
 * plus dedicated slots for {@code @SlowTest(slotGroup = ...)}.
 * <p>
 * This guarantees isolated databases for parallel execution.
 */
public final class ThreadBoundContextCustomizer implements ContextCustomizer {

    private static final Logger log = LoggerFactory.getLogger(ThreadBoundContextCustomizer.class);

    // must match parallelism from junit-platform.properties
    public static final int POOL_SIZE = 4;
    private static final SlotAllocator DEFAULT_ALLOCATOR = new SlotAllocator(POOL_SIZE);
    private static final Set<Class<?>> BASE_IMPORT_CLASSES = resolveBaseImportClasses();

    private static Set<Class<?>> resolveBaseImportClasses() {
        Import importAnn = SpringIntegrationTest.class.getAnnotation(Import.class);
        if (importAnn == null) {
            return Set.of();
        }
        Set<Class<?>> classes = new HashSet<>(Arrays.asList(importAnn.value()));
        return Collections.unmodifiableSet(classes);
    }

    public static int getSlotForClass(Class<?> testClass) {
        return DEFAULT_ALLOCATOR.getSlotForClass(testClass);
    }

    /** Returns the slot assignment strategy for reporting purposes. */
    public static String getSlotModeForClass(Class<?> testClass) {
        return DEFAULT_ALLOCATOR.getSlotModeForClass(testClass);
    }

    static SlotAllocator createAllocatorForTest(int poolSize) {
        return new SlotAllocator(poolSize);
    }

    private final int slot;
    private final String testClassName;
    private final String slotMode;

    ThreadBoundContextCustomizer(int slot, String testClassName, String slotMode) {
        this.slot = slot;
        this.testClassName = testClassName;
        this.slotMode = slotMode;
    }

    static ThreadBoundContextCustomizer forClass(Class<?> testClass) {
        int slot = getSlotForClass(testClass);
        String slotMode = getSlotModeForClass(testClass);
        if (slot >= POOL_SIZE) {
            log.debug("📦 Test class '{}' assigned DEDICATED context slot {}", testClass.getSimpleName(), slot);
        } else {
            log.debug("📦 Test class '{}' assigned context slot {}/{}", testClass.getSimpleName(), slot, POOL_SIZE);
        }
        return new ThreadBoundContextCustomizer(slot, testClass.getName(), slotMode);
    }

    int getSlot() {
        return slot;
    }

    @Override
    public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
        List<String> differentiators = TestTimingRegistry.inspectDifferentiators(
                mergedConfig.getTestClass(),
                Set.of(mergedConfig.getClasses()),
                List.of(mergedConfig.getPropertySourceProperties()),
                List.of(mergedConfig.getPropertySourceLocations()),
                mergedConfig.getActiveProfiles(),
                BASE_IMPORT_CLASSES);
        TestTimingRegistry.instance()
                .recordContextCustomizer(context, slot, slotMode, mergedConfig.getTestClass(), differentiators);
    }

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

    static final class SlotAllocator {
        private final int poolSize;
        private final Map<Class<?>, Integer> slowTestSlots = new ConcurrentHashMap<>();
        private final Map<String, int[]> groupLanePools = new ConcurrentHashMap<>();
        private final Map<Class<?>, Integer> groupClassSlots = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> groupLaneCounters = new ConcurrentHashMap<>();
        private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
        private final AtomicInteger groupSlotCounter;

        SlotAllocator(int poolSize) {
            this.poolSize = poolSize;
            this.groupSlotCounter = new AtomicInteger(poolSize);
        }

        int getSlotForClass(Class<?> testClass) {
            SlowTest slowTest = testClass.getAnnotation(SlowTest.class);
            if (slowTest == null) {
                // & Integer.MAX_VALUE keeps the index non-negative for all hashCode() values
                // (Math.abs(Integer.MIN_VALUE) is negative).
                return (testClass.getName().hashCode() & Integer.MAX_VALUE) % poolSize;
            }

            String slotGroup = slowTest.slotGroup();
            if (slotGroup != null && !slotGroup.isEmpty()) {
                int lanes = Math.max(1, slowTest.maxConcurrency());
                int[] pool = groupLanePools.computeIfAbsent(slotGroup, k -> {
                    int[] allocated = new int[lanes];
                    for (int i = 0; i < lanes; i++) {
                        allocated[i] = groupSlotCounter.getAndIncrement();
                    }
                    return allocated;
                });
                return groupClassSlots.computeIfAbsent(testClass, k -> {
                    if (pool.length == 1) {
                        return pool[0];
                    }
                    int idx = groupLaneCounters
                            .computeIfAbsent(slotGroup, g -> new AtomicInteger(0))
                            .getAndIncrement();
                    return pool[Math.floorMod(idx, pool.length)];
                });
            }

            return slowTestSlots.computeIfAbsent(testClass, k -> roundRobinCounter.getAndIncrement() % poolSize);
        }

        String getSlotModeForClass(Class<?> testClass) {
            SlowTest slowTest = testClass.getAnnotation(SlowTest.class);
            if (slowTest == null) {
                return "HASH";
            }
            String slotGroup = slowTest.slotGroup();
            if (slotGroup != null && !slotGroup.isEmpty()) {
                return "GROUP:" + slotGroup;
            }
            return "SLOW_RR";
        }
    }
}
