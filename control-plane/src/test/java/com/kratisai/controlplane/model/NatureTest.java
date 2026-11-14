package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NatureTest {
    @Test
    void closedSetHasNoExtraNature() {
        assertThat(Nature.values())
                .extracting(Enum::name)
                .containsExactly("CALLS", "MESSAGES", "BUILDS_AGAINST", "DEPLOYS", "SHARES_STATE", "PEER");
    }
}
