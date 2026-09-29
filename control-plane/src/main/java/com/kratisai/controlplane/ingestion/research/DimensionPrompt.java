package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.model.DimensionCategory;
import java.util.List;

public enum DimensionPrompt {
    DOMAIN("""
            You are an expert software architect. Your task is to generate a rich synopsis for a specific business domain within the codebase.
            Domain Name: %s
            Glob Patterns: %s

            Here are snippets from the top ranked files in this business domain:
            %s

            Provide a concise, informative synopsis (2-3 sentences) describing what this business domain represents and its core responsibilities in the codebase.
            %s
            """),
    ARCHETYPE("""
            You are an expert software architect. Your task is to generate a rich synopsis for a specific architectural archetype within the codebase.
            Archetype Name: %s
            Glob Patterns: %s

            Here are snippets from the top ranked files representing this architectural archetype:
            %s

            Provide a concise, informative synopsis (2-3 sentences) describing what this architectural archetype represents and its structural role in the codebase.
            %s
            """),
    CROSS_CUTTING("""
            You are an expert software architect. Your task is to generate a rich synopsis for a specific cross-cutting utility within the codebase.
            Utility Name: %s
            Glob Patterns: %s

            Here are snippets from the top ranked files representing this cross-cutting utility:
            %s

            Provide a concise, informative synopsis (2-3 sentences) describing what this cross-cutting utility represents and how it supports the broader codebase.
            %s
            """);

    private final String template;

    DimensionPrompt(String template) {
        this.template = template;
    }

    public String format(String name, List<String> globPatterns, String fileSnippets, String outputFormat) {
        return String.format(
                template,
                name,
                globPatterns,
                fileSnippets.isEmpty() ? "No file snippets available." : fileSnippets,
                outputFormat);
    }

    public static DimensionPrompt fromCategory(DimensionCategory category) {
        return switch (category) {
            case DOMAIN -> DOMAIN;
            case ARCHETYPE -> ARCHETYPE;
            case CROSS_CUTTING -> CROSS_CUTTING;
        };
    }
}
