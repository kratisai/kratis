package com.kratisai.controlplane.api.wsdto;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record HitlResolution(
        UUID executionId,
        String hitlId,
        HitlKind kind,
        HitlResponse response,
        String optionId,
        Map<String, Object> content,
        UUID resolvedByUserId,
        String resolvedByDisplayName) {

    public HitlResolution {
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(hitlId, "hitlId is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(response, "response is required");
        content = content != null ? Map.copyOf(content) : null;
    }
}
