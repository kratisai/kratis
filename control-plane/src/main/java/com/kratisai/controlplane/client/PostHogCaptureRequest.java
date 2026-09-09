package com.kratisai.controlplane.client;

import java.util.Map;
import java.util.Objects;

public record PostHogCaptureRequest(String event, String distinctId, Map<String, Object> properties) {

    public PostHogCaptureRequest {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(distinctId, "distinctId");
        Objects.requireNonNull(properties, "properties");
        properties = Map.copyOf(properties);
    }
}
