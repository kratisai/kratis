package com.kratisai.controlplane;

import java.util.Set;
import org.junit.jupiter.api.parallel.ResourceLocksProvider;

public class DynamicContextSlotLockProvider implements ResourceLocksProvider {
    @Override
    public Set<Lock> provideForClass(Class<?> testClass) {
        int slot = ThreadBoundContextCustomizer.getSlotForClass(testClass);
        return Set.of(new Lock("SLOT_" + slot));
    }
}
