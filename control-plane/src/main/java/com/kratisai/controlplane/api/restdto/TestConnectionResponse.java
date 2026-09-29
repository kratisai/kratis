package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Response from testing a provider connection")
public record TestConnectionResponse(
        @Schema(description = "Whether the connection test was successful", example = "true")
        boolean success,

        @Schema(description = "Error message if the connection test failed", example = "Invalid API key")
        String error,

        @Schema(
                description =
                        "Models discovered from the provider, including kind and context window when the provider reports one")
        List<ModelEntryDto> models) {

    public static TestConnectionResponse success(List<ModelEntryDto> models) {
        return new TestConnectionResponse(true, null, models);
    }

    public static TestConnectionResponse failure(String error) {
        return new TestConnectionResponse(false, error, List.of());
    }
}
