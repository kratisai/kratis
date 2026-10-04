package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.HarnessCatalogFixture;
import com.kratisai.controlplane.model.AgentHarness;
import org.junit.jupiter.api.Test;

class HarnessCatalogInitializerTest {

    @Test
    void publishesTheConfiguredDirectory() {
        HarnessesProperties properties = new HarnessesProperties();
        properties.setDirectory(HarnessCatalogFixture.DIRECTORY.toString());

        new HarnessCatalogInitializer(properties);

        assertThat(AgentHarness.values()).isNotEmpty();
    }

    @Test
    void rejectsBlankDirectory() {
        HarnessesProperties properties = new HarnessesProperties();
        properties.setDirectory(" ");

        assertThatThrownBy(() -> new HarnessCatalogInitializer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kratis.harnesses.directory");
    }

    @Test
    void rejectsMissingDirectory() {
        HarnessesProperties properties = new HarnessesProperties();
        properties.setDirectory("does/not/exist");

        assertThatThrownBy(() -> new HarnessCatalogInitializer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }
}
