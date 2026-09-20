package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import java.util.UUID;

/** A pending HITL request was resolved (user, timeout, or termination). AFTER_COMMIT: UI fan-out, sidecar reply, activity update. */
public record SandboxExecutionHitlResolvedEvent(UUID teamId, ExecutionHitlResolvedResult result) {}
