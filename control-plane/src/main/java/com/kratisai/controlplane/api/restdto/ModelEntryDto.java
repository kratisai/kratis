package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.ModelKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(description = "Model entry with name, kind, and optional context window")
public record ModelEntryDto(
        @Schema(description = "Model name", example = "gpt-4o")
        String modelName,

        @Schema(description = "Model kind (CHAT or EMBEDDING)", example = "CHAT")
        ModelKind kind,

        @Schema(
                description = "Base model for pricing/type detection when it differs from the model name",
                example = "gpt-4o")
        String baseModel,

        @Schema(
                description =
                        "Model context window in tokens, discovered from the provider's model API when it reports one. Supplied to sandbox harnesses at launch time; harnesses fall back to their own default when null.",
                example = "1048576")
        Long contextWindowTokens) {

    public ModelEntryDto {
        Objects.requireNonNull(modelName, "modelName is required");
        Objects.requireNonNull(kind, "kind is required");
    }
}
