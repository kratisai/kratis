package com.kratisai.controlplane.config;

import com.kratisai.controlplane.client.PostHogCaptureClient;
import com.kratisai.controlplane.client.PostHogClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PostHogClientConfig {

    @Bean
    public PostHogClient postHogClient(TelemetryProperties properties) {
        return new PostHogCaptureClient(properties.getEndpoint(), properties.getHttpTimeoutMs());
    }
}
