package com.kratisai.controlplane.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RelationRationales {

    private RelationRationales() {}

    public static List<String> extract(String citations, UUID relationId, ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (citations == null) {
            return List.of();
        }
        try {
            JsonNode matches = objectMapper.readTree(citations).path("matches");
            if (!matches.isArray()) {
                return List.of();
            }
            List<String> rationales = new ArrayList<>();
            for (JsonNode match : matches) {
                JsonNode rationale = match.get("rationale");
                if (rationale != null
                        && rationale.isTextual()
                        && !rationale.asText().isBlank()) {
                    rationales.add(rationale.asText());
                }
            }
            return rationales.stream().distinct().toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid repository relation citations for " + relationId, e);
        }
    }
}
