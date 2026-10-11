package com.kratisai.controlplane.api.restdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request payload toggling the per-topic allow-all permission mode")
public record UpdateChatPermissionsRequest(
        @Schema(
                description = "When true, command approvals in the topic are auto-approved without prompting",
                example = "true")
        @NotNull(message = "allowAllPermissions is required")
        @JsonProperty("allowAllPermissions")
        Boolean allowAllPermissions) {}
