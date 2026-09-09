package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import java.util.Map;
import java.util.UUID;

public record SandboxExecutionHitlResolvedEvent(
        UUID teamId,
        UUID executionId,
        String hitlId,
        HitlKind kind,
        HitlResponse response,
        String optionId,
        Map<String, Object> content,
        UUID resolvedByUserId,
        String resolvedByDisplayName) {}
