package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LiteLLMPropertiesTest {

    @Test
    void getUsageRefreshInterval_returnsConfiguredValue() {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setUsageRefreshInterval(Duration.ofSeconds(30));

        assertThat(properties.getUsageRefreshInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void getUsageRefreshInterval_defaultsTo15Seconds() {
        LiteLLMProperties properties = new LiteLLMProperties();

        assertThat(properties.getUsageRefreshInterval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void getSandboxBaseUrl_fallsBackToBaseUrlWhenUnset() {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setBaseUrl("http://localhost:4000");

        assertThat(properties.getSandboxBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void getSandboxBaseUrl_returnsSandboxUrlWhenSet() {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setBaseUrl("http://localhost:4000");
        properties.setSandboxBaseUrl("http://host.docker.internal:4000");

        assertThat(properties.getSandboxBaseUrl()).isEqualTo("http://host.docker.internal:4000");
    }

    @Test
    void getSandboxBaseUrl_ignoresBlankValue() {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setBaseUrl("http://litellm:4000");
        properties.setSandboxBaseUrl(" ");

        assertThat(properties.getSandboxBaseUrl()).isEqualTo("http://litellm:4000");
    }

    @Test
    void getUsageFinalizeDelay_defaultsTo3Seconds() {
        LiteLLMProperties properties = new LiteLLMProperties();

        assertThat(properties.getUsageFinalizeDelay()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void getUsageFinalizeDelay_returnsConfiguredValue() {
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.setUsageFinalizeDelay(Duration.ofSeconds(7));

        assertThat(properties.getUsageFinalizeDelay()).isEqualTo(Duration.ofSeconds(7));
    }
}
