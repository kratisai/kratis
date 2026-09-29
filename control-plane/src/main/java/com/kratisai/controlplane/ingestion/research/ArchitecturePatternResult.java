package com.kratisai.controlplane.ingestion.research;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public record ArchitecturePatternResult(
        @JsonPropertyDescription("Brief name for the pattern")
        String name,

        @JsonPropertyDescription("Explain the pattern, and how it is used")
        String description,

        @JsonPropertyDescription("Example files demonstrating the pattern")
        List<String> exemplarPaths) {}
