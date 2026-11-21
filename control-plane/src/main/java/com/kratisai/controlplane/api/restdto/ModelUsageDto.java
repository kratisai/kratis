package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.ModelKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.Objects;

// Never map IngestionModelUsage.virtualKey into this DTO: it is a secret credential.
@Schema(description = "LLM model usage details for an activity or batch")
public record ModelUsageDto(
        @Schema(description = "Model kind (e.g. CHAT, EMBEDDING)", example = "CHAT")
        ModelKind modelKind,

        @Schema(description = "Model identifier", example = "claude-sonnet-4-5")
        String modelIdentifier,

        @Schema(description = "LiteLLM alias", example = "anthropic/claude-sonnet-4-5")
        String litellmAlias,

        @Schema(description = "Spend in USD for this model", example = "0.42")
        Double totalSpend,

        @Schema(description = "Tokens used by this model", example = "1234")
        Long totalTokens)
        implements Comparable<ModelUsageDto> {

    private static final Comparator<ModelUsageDto> COMPARATOR = Comparator.comparing(
                    (ModelUsageDto m) -> m.modelKind() != null ? m.modelKind().name() : "",
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(m -> m.modelIdentifier() != null ? m.modelIdentifier() : "", String.CASE_INSENSITIVE_ORDER);

    public ModelUsageDto {
        Objects.requireNonNull(modelIdentifier, "modelIdentifier is required");
    }

    @Override
    public int compareTo(ModelUsageDto other) {
        if (other == null) {
            return 1;
        }
        return COMPARATOR.compare(this, other);
    }
}
