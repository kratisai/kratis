package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.api.wsdto.ActivityDiff;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A HITL request (command approval or form question) has been raised for an execution. AFTER_COMMIT:
 * UI fan-out + activity log persistence.
 */
public record SandboxExecutionHitlRequiredEvent(
        UUID teamId,
        UUID executionId,
        String hitlId,
        String message,
        HitlKind kind,
        String command,
        String title,
        String toolKind,
        List<PermissionOption> options,
        ActivityDiff diff,
        Map<String, Object> form) {}
