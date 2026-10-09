package com.kratisai.controlplane.api.wsdto;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record HitlResolution(
        UUID executionId,
        String actionId,
        HitlKind kind,
        HitlResponse response,
        String optionId,
        Map<String, Object> content,
        HitlResolver resolvedBy) {

    public HitlResolution {
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(actionId, "actionId is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(response, "response is required");
        Objects.requireNonNull(resolvedBy, "resolvedBy is required");
        content = content != null ? Map.copyOf(content) : null;
    }
}
