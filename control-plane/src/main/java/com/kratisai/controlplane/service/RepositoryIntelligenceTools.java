package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class RepositoryIntelligenceTools {
    private static final Logger logger = LoggerFactory.getLogger(RepositoryIntelligenceTools.class);

    private final CtxArchitecturePatternRepository patternRepository;
    private final CtxDimensionRepository dimensionRepository;
    private final CtxNodeDimensionRepository nodeDimensionRepository;
    private final ObjectMapper objectMapper;

    public RepositoryIntelligenceTools(
            CtxArchitecturePatternRepository patternRepository,
            CtxDimensionRepository dimensionRepository,
            CtxNodeDimensionRepository nodeDimensionRepository,
            ObjectMapper objectMapper) {
        this.patternRepository = patternRepository;
        this.dimensionRepository = dimensionRepository;
        this.nodeDimensionRepository = nodeDimensionRepository;
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "get_architectural_pattens",
            description = "Get the globally discovered architectural patterns for the repository")
    public String getArchitecturalPatterns(ToolContext toolContext) {
        try {
            UUID batchId = (UUID) toolContext.getContext().get("batchId");
            List<CtxArchitecturePattern> patterns = patternRepository.findByBatchIdWithExemplarNodes(batchId);
            var patternDescriptions = patterns.stream()
                    .map(ctxPattern -> Map.of(
                            "description", ctxPattern.getDescription(),
                            "examples",
                                    ctxPattern.getExemplarNodes().stream()
                                            .map(node -> node.getPath() + ":" + node.getSymbolName())
                                            .collect(Collectors.joining(","))))
                    .toList();
            return objectMapper.writeValueAsString(patternDescriptions);
        } catch (Exception e) {
            logger.error("Error retrieving patterns: {}", e.getMessage(), e);
            return "Error retrieving patterns";
        }
    }

    @Tool(name = "get_dimensions", description = "Get the parallel code dimensions discovered in the repository")
    public String getDimensions(ToolContext toolContext) {
        try {
            UUID batchId = (UUID) toolContext.getContext().get("batchId");
            List<CtxDimension> dimensions = dimensionRepository.findByBatchId(batchId);
            var dimensionDescriptions = dimensions.stream()
                    .map(dim -> {
                        List<CtxNodeDimension> nodeDimensions = nodeDimensionRepository.findByDimensionId(dim.getId());
                        List<String> topFiles = nodeDimensions.stream()
                                .sorted((a, b) -> Double.compare(
                                        b.getRankScore() != null ? b.getRankScore() : 0.0,
                                        a.getRankScore() != null ? a.getRankScore() : 0.0))
                                .limit(10)
                                .map(nd -> nd.getNode().getPath()
                                        + (nd.getNode().getSymbolName() != null
                                                ? ":" + nd.getNode().getSymbolName()
                                                : ""))
                                .toList();
                        return Map.of(
                                "name",
                                dim.getName(),
                                "synopsis",
                                dim.getSynopsis() != null ? dim.getSynopsis() : "",
                                "topFiles",
                                topFiles);
                    })
                    .toList();
            return objectMapper.writeValueAsString(dimensionDescriptions);
        } catch (Exception e) {
            logger.error("Error retrieving dimensions: {}", e.getMessage(), e);
            return "Error retrieving dimensions";
        }
    }
}
