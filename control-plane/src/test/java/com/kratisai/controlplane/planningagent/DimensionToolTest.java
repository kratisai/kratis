package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.service.BatchResolutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class DimensionToolTest {

    @Mock
    private CtxDimensionRepository ctxDimensionRepository;

    @Mock
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Mock
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Mock
    private BatchResolutionService batchResolutionService;

    private DimensionTool dimensionTool;

    private UUID teamId;
    private UUID batchId;
    private ToolContext toolContext;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        dimensionTool = new DimensionTool(
                ctxDimensionRepository,
                ctxNodeDimensionRepository,
                ctxArchitecturePatternRepository,
                batchResolutionService);

        teamId = UUID.randomUUID();
        batchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of("teamId", teamId, "chatId", UUID.randomUUID()));

        batch = new IngestionBatch();
        batch.setId(batchId);
    }

    @Test
    void listDimensions_returnsDimensionsWithTopFiles() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxDimension dimension = createDimension("Booking", DimensionCategory.DOMAIN, "Handles bookings");
        dimension.setId(UUID.randomUUID());

        when(ctxDimensionRepository.findByBatchId(batchId)).thenReturn(List.of(dimension));

        List<DimensionTool.DimensionOverview> result = dimensionTool.listDimensions("test-repo", toolContext);

        assertThat(result).hasSize(1);
        DimensionTool.DimensionOverview overview = result.getFirst();
        assertThat(overview.name()).isEqualTo("Booking");
        assertThat(overview.category()).isEqualTo("DOMAIN");
        assertThat(overview.synopsis()).isEqualTo("Handles bookings");
    }

    @Test
    void listDimensions_filtersNullRankScores() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxDimension dimension =
                createDimension("Auth", DimensionCategory.CROSS_CUTTING, "Authentication is important");
        dimension.setId(UUID.randomUUID());

        when(ctxDimensionRepository.findByBatchId(batchId)).thenReturn(List.of(dimension));

        List<DimensionTool.DimensionOverview> result = dimensionTool.listDimensions("test-repo", toolContext);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("Auth");
        assertThat(result.getFirst().category()).isEqualTo("CROSS_CUTTING");
        assertThat(result.getFirst().synopsis()).isEqualTo("Authentication is important");
    }

    @Test
    void getDimension_returnsFullFileList() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxDimension dimension = createDimension("Booking", DimensionCategory.DOMAIN, "Handles bookings");
        dimension.setId(UUID.randomUUID());

        CtxNode node1 = createNode("src/Booking.java", "Booking", NodeType.CLASS);
        CtxNode node2 = createNode("src/BookingService.java", "BookingService", NodeType.CLASS);

        CtxNodeDimension nd1 = createNodeDimension(node1, dimension, 0.95);
        CtxNodeDimension nd2 = createNodeDimension(node2, dimension, 0.85);

        when(ctxDimensionRepository.findByBatchId(batchId)).thenReturn(List.of(dimension));
        when(ctxNodeDimensionRepository.findByDimensionIdWithNode(dimension.getId()))
                .thenReturn(List.of(nd1, nd2));

        DimensionTool.DimensionDetail result = dimensionTool.getDimension("test-repo", "Booking", toolContext);

        assertThat(result.name()).isEqualTo("Booking");
        assertThat(result.category()).isEqualTo("DOMAIN");
        assertThat(result.synopsis()).isEqualTo("Handles bookings");
        assertThat(result.files()).hasSize(2);
        assertThat(result.files().getFirst().path()).isEqualTo("src/Booking.java");
        assertThat(result.files().getFirst().symbolName()).isEqualTo("Booking");
        assertThat(result.files().getFirst().rankScore()).isEqualTo(0.95);
    }

    @Test
    void getDimension_withNonExistentDimension_throwsException() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        when(ctxDimensionRepository.findByBatchId(batchId)).thenReturn(List.of());

        assertThatThrownBy(() -> dimensionTool.getDimension("test-repo", "NonExistent", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dimension 'NonExistent' not found");
    }

    @Test
    void getDimension_caseInsensitiveMatch() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxDimension dimension = createDimension("Booking", DimensionCategory.DOMAIN, "Handles bookings");
        dimension.setId(UUID.randomUUID());

        when(ctxDimensionRepository.findByBatchId(batchId)).thenReturn(List.of(dimension));
        when(ctxNodeDimensionRepository.findByDimensionIdWithNode(dimension.getId()))
                .thenReturn(List.of());

        DimensionTool.DimensionDetail result = dimensionTool.getDimension("test-repo", "BOOKING", toolContext);

        assertThat(result.name()).isEqualTo("Booking");
    }

    @Test
    void listArchitecturePatterns_returnsPatternsWithExemplars() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxArchitecturePattern pattern = new CtxArchitecturePattern();
        pattern.setName("Hexagonal Architecture");
        pattern.setDescription("Ports and adapters pattern");

        CtxNode exemplar1 = createNode("src/Port.java", "Port", NodeType.INTERFACE);
        CtxNode exemplar2 = createNode("src/Adapter.java", "Adapter", NodeType.CLASS);
        pattern.setExemplarNodes(List.of(exemplar1, exemplar2));

        when(ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batchId))
                .thenReturn(List.of(pattern));

        List<DimensionTool.PatternSummary> result = dimensionTool.listArchitecturePatterns("test-repo", toolContext);

        assertThat(result).hasSize(1);
        DimensionTool.PatternSummary summary = result.getFirst();
        assertThat(summary.name()).isEqualTo("Hexagonal Architecture");
        assertThat(summary.description()).isEqualTo("Ports and adapters pattern");
        assertThat(summary.exemplarFiles()).containsExactly("src/Port.java:Port", "src/Adapter.java:Adapter");
    }

    @Test
    void listArchitecturePatterns_withNullSymbolName_excludesSymbol() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxArchitecturePattern pattern = new CtxArchitecturePattern();
        pattern.setName("Simple Pattern");
        pattern.setDescription("A simple pattern");

        CtxNode exemplar = createNode("src/File.java", null, NodeType.FILE);
        pattern.setExemplarNodes(List.of(exemplar));

        when(ctxArchitecturePatternRepository.findByBatchIdWithExemplarNodes(batchId))
                .thenReturn(List.of(pattern));

        List<DimensionTool.PatternSummary> result = dimensionTool.listArchitecturePatterns("test-repo", toolContext);

        assertThat(result.getFirst().exemplarFiles()).containsExactly("src/File.java");
    }

    private CtxDimension createDimension(String name, DimensionCategory category, String synopsis) {
        CtxDimension dimension = new CtxDimension();
        dimension.setName(name);
        dimension.setCategory(category);
        dimension.setSynopsis(synopsis);
        dimension.setGlobPatterns(List.of("**/" + name.toLowerCase() + "/**"));
        return dimension;
    }

    private CtxNode createNode(String path, String symbolName, NodeType nodeType) {
        CtxNode node = new CtxNode();
        node.setPath(path);
        node.setSymbolName(symbolName);
        node.setNodeType(nodeType);
        return node;
    }

    private CtxNodeDimension createNodeDimension(CtxNode node, CtxDimension dimension, Double rankScore) {
        CtxNodeDimension nd = new CtxNodeDimension();
        nd.setNode(node);
        nd.setDimension(dimension);
        nd.setRankScore(rankScore);
        return nd;
    }
}
