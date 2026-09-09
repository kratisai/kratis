package com.kratisai.controlplane.model.event;

import java.util.UUID;

/**
 * Event published when a global entity belonging to a specific user changes. This signals clients
 * to invalidate user-scoped global caches (like their team list).
 */
public record UserEntityChangedEvent(UUID userId, UserEntityType type) {}
