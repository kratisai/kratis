package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestionBatchStatsDtoTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void gettersAndSetters_shouldRoundTripAllFields() {
        IngestionBatchStatsDto dto = createFullyPopulatedDto();

        assertThat(dto.getBatchId()).isEqualTo("batch-1");
        assertThat(dto.getTotalDurationSeconds()).isEqualTo(120L);
        assertThat(dto.getTotalNodes()).isEqualTo(42);
        assertThat(dto.getTotalEdges()).isEqualTo(84);
        assertThat(dto.getNodeTypeCounts()).containsEntry(NodeType.CLASS, 10);
        assertThat(dto.getEdgeTypeCounts()).containsEntry(RelationType.IMPORTS, 5);
        assertThat(dto.getDimensions()).hasSize(1);
        assertThat(dto.getDimensions().getFirst().name()).isEqualTo("Auth");
        assertThat(dto.getArchitecturePatterns()).hasSize(1);
        assertThat(dto.getArchitecturePatterns().getFirst().name()).isEqualTo("Layered");
        assertThat(dto.getWikiPageSlugs()).containsExactly("home", "architecture");
        assertThat(dto.getTotalSpend()).isEqualTo(0.42);
        assertThat(dto.getTotalTokens()).isEqualTo(1234L);
        assertThat(dto.getPromptTokens()).isEqualTo(900L);
        assertThat(dto.getCompletionTokens()).isEqualTo(334L);
        assertThat(dto.getTotalToolCalls()).isEqualTo(12L);
        assertThat(dto.getUsageLastUpdatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void setters_shouldUpdateAllFields() {
        IngestionBatchStatsDto dto = new IngestionBatchStatsDto(
                null, null, 0, 0, null, null, List.of(), List.of(), List.of(), null, null, null, null, null, null);

        dto.setBatchId("updated-batch");
        dto.setTotalDurationSeconds(999L);
        dto.setTotalNodes(1);
        dto.setTotalEdges(2);
        dto.setNodeTypeCounts(Map.of(NodeType.INTERFACE, 3));
        dto.setEdgeTypeCounts(Map.of(RelationType.IMPLEMENTS, 4));
        dto.setDimensions(List.of());
        dto.setArchitecturePatterns(List.of());
        dto.setWikiPageSlugs(List.of());
        dto.setTotalSpend(0.01);
        dto.setTotalTokens(10L);
        dto.setPromptTokens(5L);
        dto.setCompletionTokens(5L);
        dto.setTotalToolCalls(1L);
        dto.setUsageLastUpdatedAt(Instant.ofEpochSecond(1000));

        assertThat(dto.getBatchId()).isEqualTo("updated-batch");
        assertThat(dto.getTotalDurationSeconds()).isEqualTo(999L);
        assertThat(dto.getTotalNodes()).isEqualTo(1);
        assertThat(dto.getTotalEdges()).isEqualTo(2);
        assertThat(dto.getNodeTypeCounts()).containsEntry(NodeType.INTERFACE, 3);
        assertThat(dto.getEdgeTypeCounts()).containsEntry(RelationType.IMPLEMENTS, 4);
        assertThat(dto.getDimensions()).isEmpty();
        assertThat(dto.getArchitecturePatterns()).isEmpty();
        assertThat(dto.getWikiPageSlugs()).isEmpty();
        assertThat(dto.getTotalSpend()).isEqualTo(0.01);
        assertThat(dto.getTotalTokens()).isEqualTo(10L);
        assertThat(dto.getPromptTokens()).isEqualTo(5L);
        assertThat(dto.getCompletionTokens()).isEqualTo(5L);
        assertThat(dto.getTotalToolCalls()).isEqualTo(1L);
        assertThat(dto.getUsageLastUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1000));
    }

    @Test
    void serialization_shouldProduceJson() throws Exception {
        IngestionBatchStatsDto dto = new IngestionBatchStatsDto(
                "batch-1",
                120L,
                42,
                84,
                Map.of(NodeType.CLASS, 10),
                Map.of(RelationType.IMPORTS, 5),
                List.of(new DimensionStatDto(null, "Auth", 3, "Authentication", List.of("Auth.java"))),
                List.of(new ArchitecturePatternDto("Layered", "Layered architecture")),
                List.of("home", "architecture"),
                0.42,
                1234L,
                900L,
                334L,
                12L,
                null);

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("batch-1");
        assertThat(json).contains("42");
        assertThat(json).contains("0.42");
    }

    private static IngestionBatchStatsDto createFullyPopulatedDto() {
        return new IngestionBatchStatsDto(
                "batch-1",
                120L,
                42,
                84,
                Map.of(NodeType.CLASS, 10),
                Map.of(RelationType.IMPORTS, 5),
                List.of(new DimensionStatDto(null, "Auth", 3, "Authentication", List.of("Auth.java"))),
                List.of(new ArchitecturePatternDto("Layered", "Layered architecture")),
                List.of("home", "architecture"),
                0.42,
                1234L,
                900L,
                334L,
                12L,
                Instant.EPOCH);
    }
}
