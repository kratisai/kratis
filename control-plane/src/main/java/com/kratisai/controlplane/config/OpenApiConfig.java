package com.kratisai.controlplane.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Kratis Control Plane API",
                        description = "REST API for the Kratis AI development agent platform"),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER,
        description = "JWT Authorization header using the Bearer scheme. Example: \"Authorization: Bearer {token}\"")
public class OpenApiConfig {
    // This class is used solely for OpenAPI annotations
    // No implementation needed - Spring Boot auto-configures springdoc
}
