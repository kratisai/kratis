package com.kratisai.controlplane.api.wsdto;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record HitlRequestSnapshot(
        UUID executionId,
        String hitlId,
        HitlKind kind,
        String message,
        String command,
        List<CommandSegment> commandSegments,
        String title,
        String toolKind,
        List<PermissionOption> options,
        ActivityDiff diff,
        Map<String, Object> form) {

    public HitlRequestSnapshot {
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(hitlId, "hitlId is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(message, "message is required");
        commandSegments = commandSegments != null ? List.copyOf(commandSegments) : null;
        options = options != null ? List.copyOf(options) : null;
        form = form != null ? Map.copyOf(form) : null;
    }

    public HitlRequestSnapshot(
            UUID executionId,
            String hitlId,
            HitlKind kind,
            String message,
            String command,
            String title,
            String toolKind,
            List<PermissionOption> options,
            ActivityDiff diff,
            Map<String, Object> form) {
        this(executionId, hitlId, kind, message, command, null, title, toolKind, options, diff, form);
    }

    public HitlRequestSnapshot(UUID executionId, String hitlId, HitlKind kind, String message) {
        this(executionId, hitlId, kind, message, null, null, null, null, null, null, null);
    }
}
