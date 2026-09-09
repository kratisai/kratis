package com.kratisai.controlplane.ingestion.parse;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record DimensionDiscoveryResult(
        List<DimensionResult> domains,
        List<DimensionResult> archetypes,
        List<DimensionResult> crossCutting,

        @JsonPropertyDescription("Request for additional context **only use if the dimensions are ambiguous**")
        AdditionalContextRequest additionalContextRequest) {

    public record DimensionResult(String name, List<String> globPatterns) {}

    public record AdditionalContextRequest(
            List<String> ambiguousFilePaths,
            String rationale,
            List<String> certainDimensions,
            String expectedResolution) {}
}
