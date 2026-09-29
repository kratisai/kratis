package com.kratisai.controlplane.model.event;

import java.util.UUID;

/**
 * Event published when a scoped entity belonging to a specific team changes. This signals clients
 * viewing that specific team to invalidate their local caches.
 */
public record TeamEntityChangedEvent(UUID teamId, TeamEntityType type) {}
