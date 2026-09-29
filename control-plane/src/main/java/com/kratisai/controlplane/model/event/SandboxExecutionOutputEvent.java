package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.api.wsdto.OutputStream;
import java.util.UUID;

public record SandboxExecutionOutputEvent(UUID teamId, UUID executionId, String line, OutputStream stream) {}
