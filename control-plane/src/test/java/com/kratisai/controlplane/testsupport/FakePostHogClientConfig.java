package com.kratisai.controlplane.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Registers a shared {@link FakePostHogClient} so telemetry tests share the standard context. */
@TestConfiguration
public class FakePostHogClientConfig {

    @Bean
    @Primary
    public FakePostHogClient fakePostHogClient() {
        return new FakePostHogClient();
    }
}
