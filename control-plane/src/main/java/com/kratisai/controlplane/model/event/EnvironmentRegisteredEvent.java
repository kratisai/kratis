package com.kratisai.controlplane.model.event;

import java.util.UUID;

/**
 * Event published when a sidecar execution environment registers successfully.
 */
public record EnvironmentRegisteredEvent(UUID environmentId) {}
