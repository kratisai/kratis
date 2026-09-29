package com.kratisai.controlplane.model.event;

import java.util.UUID;

/**
 * Event published when a sidecar execution environment connection is closed.
 */
public record EnvironmentDisconnectedEvent(UUID environmentId) {}
