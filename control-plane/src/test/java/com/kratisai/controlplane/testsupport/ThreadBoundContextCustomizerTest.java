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
    void respectsExplicitLaneWrappingAndEnvironmentMaxConcurrencyOverrides() {
        var envScoped = ThreadBoundContextCustomizer.createAllocatorForTest(
                4, key -> "kratis.test.slot-group.MULTI.max-concurrency".equals(key) ? "2" : null, key -> null);

        int lane0 = envScoped.getSlotForClass(PinnedLane0.class);
        int lane1 = envScoped.getSlotForClass(PinnedLane1.class);
        int lane2Wrapped = envScoped.getSlotForClass(PinnedLane2.class);
        int lane3Wrapped = envScoped.getSlotForClass(PinnedLane3.class);

        assertThat(Set.of(lane0, lane1)).hasSize(2).allSatisfy(s -> assertThat(s)
                .isBetween(4, 5));
        assertThat(lane2Wrapped).isEqualTo(lane0);
        assertThat(lane3Wrapped).isEqualTo(lane1);

        var envVarScoped = ThreadBoundContextCustomizer.createAllocatorForTest(
                4, key -> null, key -> "KRATIS_TEST_SLOT_GROUP_END_TO_END_MAX_CONCURRENCY".equals(key) ? "3" : null);
        assertThat(ThreadBoundContextCustomizer.SlotAllocator.resolveMaxConcurrency(
                        "END_TO_END",
                        4,
                        key -> null,
                        key -> "KRATIS_TEST_SLOT_GROUP_END_TO_END_MAX_CONCURRENCY".equals(key) ? "3" : null))
                .isEqualTo(3);
        int e0 = envVarScoped.getSlotForClass(E2eLane0.class);
        int e1 = envVarScoped.getSlotForClass(E2eLane1.class);
        int e2 = envVarScoped.getSlotForClass(E2eLane2.class);
        int e3Wrapped = envVarScoped.getSlotForClass(E2eLane3.class);
        assertThat(Set.of(e0, e1, e2)).hasSize(3);
        assertThat(e3Wrapped).isEqualTo(e0);
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

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 4, lane = 0)
    private static class PinnedLane0 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 4, lane = 1)
    private static class PinnedLane1 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 4, lane = 2)
    private static class PinnedLane2 {}

    @SlowTest(slotGroup = "MULTI", maxConcurrency = 4, lane = 3)
    private static class PinnedLane3 {}

    @SlowTest(slotGroup = "END_TO_END", maxConcurrency = 4, lane = 0)
    private static class E2eLane0 {}

    @SlowTest(slotGroup = "END_TO_END", maxConcurrency = 4, lane = 1)
    private static class E2eLane1 {}

    @SlowTest(slotGroup = "END_TO_END", maxConcurrency = 4, lane = 2)
    private static class E2eLane2 {}

    @SlowTest(slotGroup = "END_TO_END", maxConcurrency = 4, lane = 3)
    private static class E2eLane3 {}
}
