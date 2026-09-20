package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import java.util.UUID;

/** A HITL request (approval or question) has been raised. AFTER_COMMIT: UI fan-out + activity log persistence. */
public record SandboxExecutionHitlRequiredEvent(UUID teamId, ExecutionHitlRequiredResult result) {}
