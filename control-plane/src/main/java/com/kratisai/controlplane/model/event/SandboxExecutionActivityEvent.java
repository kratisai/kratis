package com.kratisai.controlplane.model.event;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import java.util.UUID;

public record SandboxExecutionActivityEvent(
        UUID teamId,
        UUID executionId,
        ActivityType activityType,
        String description,
        String actionId,
        ActivityStatus status,
        ActivityDetail detail) {}
