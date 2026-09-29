package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "Installation details for the running Kratis instance")
public record InstallationInfoDto(
        @Schema(description = "Unique installation UUID", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
        UUID installId,

        @Schema(description = "Kratis software version", example = "1.0.0")
        String version) {

    public InstallationInfoDto {
        Objects.requireNonNull(installId, "installId must not be null");
    }
}
