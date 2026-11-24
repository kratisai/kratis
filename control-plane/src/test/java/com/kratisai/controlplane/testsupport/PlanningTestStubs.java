package com.kratisai.controlplane.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.service.ScratchpadService;

/** Scratchpad doubles for planning-agent tests, matching the real service's non-null contract. */
public final class PlanningTestStubs {

    private PlanningTestStubs() {}

    /** A scratchpad with no stored facts. Unstubbed mocks return {@code null}, which the real service never does. */
    public static ScratchpadService emptyScratchpad() {
        ScratchpadService scratchpad = mock(ScratchpadService.class);
        when(scratchpad.getFactsAsString(any())).thenReturn("");
        return scratchpad;
    }
}
