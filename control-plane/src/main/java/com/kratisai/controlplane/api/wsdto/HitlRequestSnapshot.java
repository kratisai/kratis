package com.kratisai.controlplane.api.wsdto;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record HitlRequestSnapshot(
        UUID executionId,
        String actionId,
        HitlKind kind,
        String message,
        String command,
        List<CommandSegment> commandSegments,
        String title,
        String toolKind,
        List<PermissionOption> options,
        ActivityDiff diff,
        Map<String, Object> form,
        List<ActivityLocation> locations) {

    public HitlRequestSnapshot {
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(actionId, "actionId is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(message, "message is required");
        commandSegments = commandSegments != null ? List.copyOf(commandSegments) : null;
        options = options != null ? List.copyOf(options) : null;
        form = form != null ? Map.copyOf(form) : null;
        locations = locations != null ? List.copyOf(locations) : null;
    }
}
