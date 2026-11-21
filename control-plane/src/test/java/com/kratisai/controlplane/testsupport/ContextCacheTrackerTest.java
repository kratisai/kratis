package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.TestContext;

class ContextCacheTrackerTest {

    @Test
    void resolvedContext_returnsCachedActiveContextWithoutLockingTestContextCache() {
        ContextCacheTracker tracker = new ContextCacheTracker(TestTimingRegistry.createForTest());
        try (GenericApplicationContext appCtx = new GenericApplicationContext()) {
            appCtx.refresh();

            TestContext prepareCtx = mock(TestContext.class);
            when(prepareCtx.getTestClass()).thenAnswer(inv -> SampleClass.class);
            when(prepareCtx.hasApplicationContext()).thenReturn(true);
            when(prepareCtx.getApplicationContext()).thenReturn(appCtx);

            tracker.beforeTestClass(prepareCtx);
            tracker.prepareTestInstance(prepareCtx);

            // Subsequent listener lookups must resolve the cached context without touching TestContext
            TestContext methodCtx = mock(TestContext.class);
            when(methodCtx.getTestClass()).thenAnswer(inv -> SampleClass.class);

            assertThat(ContextCacheTracker.resolvedContext(methodCtx)).isSameAs(appCtx);
            verify(methodCtx, never()).hasApplicationContext();
            verify(methodCtx, never()).getApplicationContext();

            tracker.afterTestClass(prepareCtx);
            when(methodCtx.hasApplicationContext()).thenReturn(false);
            assertThat(ContextCacheTracker.resolvedContext(methodCtx)).isNull();
        }
    }

    private static class SampleClass {}
}
