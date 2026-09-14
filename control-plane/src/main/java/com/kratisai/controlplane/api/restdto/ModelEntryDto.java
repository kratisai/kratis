package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.ModelKind;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Model entry with name and kind")
public record ModelEntryDto(
        @Schema(description = "Model name", example = "gpt-4o")
        String modelName,

        @Schema(description = "Model kind (CHAT or EMBEDDING)", example = "CHAT")
        ModelKind kind,

        @Schema(
                description = "Base model for pricing/type detection when it differs from the model name",
                example = "gpt-4o")
        String baseModel) {
    public ModelEntryDto(String modelName, ModelKind kind) {
        this(modelName, kind, null);
    }
}
