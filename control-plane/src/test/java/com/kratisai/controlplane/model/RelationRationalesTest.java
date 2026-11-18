package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelationRationalesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID relationId = UUID.randomUUID();

    @Test
    void extractReadsDistinctTextFromMatchCitations() {
        String citations = """
                {"matches":[
                  {"rationale":"First match"},
                  {"rationale":"Second match"},
                  {"rationale":"First match"},
                  {"rationale":"   "},
                  {"rationale":null},
                  {"rationale":42}
                ]}
                """;

        assertThat(RelationRationales.extract(citations, relationId, objectMapper))
                .containsExactly("First match", "Second match");
    }

    @Test
    void extractReturnsEmptyWhenCitationsAreMissingOrHaveNoMatches() {
        assertThat(RelationRationales.extract(null, relationId, objectMapper)).isEmpty();
        assertThat(RelationRationales.extract("{\"sources\":[\"old\"]}", relationId, objectMapper))
                .isEmpty();
        assertThat(RelationRationales.extract("{\"matches\":[]}", relationId, objectMapper))
                .isEmpty();
    }

    @Test
    void extractRejectsMalformedCitations() {
        assertThatThrownBy(() -> RelationRationales.extract("{", relationId, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid repository relation citations for " + relationId);
    }
}
