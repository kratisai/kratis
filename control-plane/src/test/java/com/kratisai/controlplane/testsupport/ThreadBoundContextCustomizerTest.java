package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLocksProvider;

class ThreadBoundContextCustomizerTest {

    @Test
    void partitionsDefaultTestsAcrossFixedPool() {
        var allocator = ThreadBoundContextCustomizer.createAllocatorForTest(4);
        int slotA = allocator.getSlotForClass(DefaultA.class);
        int slotB = allocator.getSlotForClass(DefaultB.class);

        assertThat(slotA).isBetween(0, 3);
        assertThat(slotB).isBetween(0, 3);
        assertThat(allocator.getSlotForClass(DefaultA.class)).isEqualTo(slotA);
        assertThat(allocator.getSlotModeForClass(DefaultA.class)).isEqualTo("HASH");
    }

    @Test
    void allocatesSingleDedicatedSlotForSingleLaneGroup() {
        var allocator = ThreadBoundContextCustomizer.createAllocatorForTest(4);
        int s1 = allocator.getSlotForClass(SingleLane1.class);
        int s2 = allocator.getSlotForClass(SingleLane2.class);

        assertThat(s1).isEqualTo(4);
        assertThat(s2).isEqualTo(4);
        assertThat(allocator.getSlotModeForClass(SingleLane1.class)).isEqualTo("GROUP:SINGLE");
    }

    @Test
    void distributesMultiLaneSlotGroupAcrossDedicatedLanesDeterministically() {
        var allocator = ThreadBoundContextCustomizer.createAllocatorForTest(4);
        int l1 = allocator.getSlotForClass(MultiLane1.class);
        int l2 = allocator.getSlotForClass(MultiLane2.class);
        int l3 = allocator.getSlotForClass(MultiLane3.class);
        int l4 = allocator.getSlotForClass(MultiLane4.class);

        assertThat(Set.of(l1, l2, l3)).hasSize(3).allSatisfy(slot -> assertThat(slot)
                .isBetween(4, 6));
        assertThat(l4).isEqualTo(l1);
        assertThat(allocator.getSlotForClass(MultiLane2.class)).isEqualTo(l2);

        // Unrelated group gets a fresh non-overlapping slot
        int other = allocator.getSlotForClass(SingleLane1.class);
        assertThat(other).isEqualTo(7);
    }

    @Test
    void customizerEqualityAndLockProviderMatchAssignedSlot() {
        var c1 = ThreadBoundContextCustomizer.forClass(SingleLane1.class);
        var c2 = ThreadBoundContextCustomizer.forClass(SingleLane2.class);
        assertThat(c1).isEqualTo(c2).hasSameHashCodeAs(c2);

        Set<ResourceLocksProvider.Lock> locks = new DynamicContextSlotLockProvider().provideForClass(SingleLane1.class);
        assertThat(locks).extracting(ResourceLocksProvider.Lock::getKey).containsExactly("SLOT_" + c1.getSlot());

        // Multi-lane group produces distinct customizer keys across lanes so each lane keeps its own Spring context
        Set<ThreadBoundContextCustomizer> multiCustomizers = new HashSet<>();
        multiCustomizers.add(ThreadBoundContextCustomizer.forClass(MultiLane1.class));
        multiCustomizers.add(ThreadBoundContextCustomizer.forClass(MultiLane2.class));
        multiCustomizers.add(ThreadBoundContextCustomizer.forClass(MultiLane3.class));
        assertThat(multiCustomizers).hasSize(3);
    }

    private static class DefaultA {}

    private static class DefaultB {}

    @SlowTest(slotGroup = "SINGLE")
    private static class SingleLane1 {}

    @SlowTest(slotGroup = "SINGLE")
    private static class SingleLane2 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 3)
    private static class MultiLane1 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 3)
    private static class MultiLane2 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 3)
    private static class MultiLane3 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 3)
    private static class MultiLane4 {}
}
