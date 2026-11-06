package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.testsupport.HarnessCatalogFixture;
import org.junit.jupiter.api.Test;

class HarnessCatalogInitializerTest {

    @Test
    void publishesTheConfiguredDirectory() {
        InstanceProperties properties = new InstanceProperties();
        properties.setHarnessesDirectory(HarnessCatalogFixture.DIRECTORY.toString());

        new HarnessCatalogInitializer(properties);

        assertThat(AgentHarness.values()).isNotEmpty();
    }

    @Test
    void rejectsBlankDirectory() {
        InstanceProperties properties = new InstanceProperties();
        properties.setHarnessesDirectory(" ");

        assertThatThrownBy(() -> new HarnessCatalogInitializer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kratis.instance.harnesses-directory");
    }

    @Test
    void rejectsMissingDirectory() {
        InstanceProperties properties = new InstanceProperties();
        properties.setHarnessesDirectory("does/not/exist");

        assertThatThrownBy(() -> new HarnessCatalogInitializer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }
}
