package com.kratisai.controlplane.ingestion.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TreeHelper {

    private static final Logger logger = LoggerFactory.getLogger(TreeHelper.class);

    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final ObjectMapper objectMapper;

    public TreeHelper(
            CtxNodeRepository ctxNodeRepository, CtxEdgeRepository ctxEdgeRepository, ObjectMapper objectMapper) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.objectMapper = objectMapper;
    }

    public String buildAnnotatedTree(IngestionBatch batch) {
        List<CtxNode> allNodes = ctxNodeRepository.findByBatchId(batch.getId());
        List<CtxNode> symbolChildren = ctxNodeRepository.findSymbolChildrenByBatchId(batch.getId());
        List<CtxEdge> inheritanceEdges = ctxEdgeRepository.findInheritanceEdgesByBatchId(batch.getId());

        // Group symbol children by file path
        Map<String, List<CtxNode>> symbolsByPath = symbolChildren.stream()
                .filter(n -> n.getPath() != null)
                .collect(Collectors.groupingBy(CtxNode::getPath));

        // Group inheritance edges by source node path
        Map<String, List<CtxEdge>> edgesBySourcePath = inheritanceEdges.stream()
                .filter(e -> e.getSourceNode() != null && e.getSourceNode().getPath() != null)
                .collect(Collectors.groupingBy(e -> e.getSourceNode().getPath()));

        return allNodes.stream()
                .filter(n -> NodeType.FILE == n.getNodeType())
                .map(CtxNode::getPath)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(path -> buildFootprintLine(path, symbolsByPath, edgesBySourcePath))
                .collect(Collectors.joining("\n"));
    }

    private String buildFootprintLine(
            String path, Map<String, List<CtxNode>> symbolsByPath, Map<String, List<CtxEdge>> edgesBySourcePath) {

        List<CtxNode> symbols = symbolsByPath.getOrDefault(path, List.of());
        if (symbols.isEmpty()) {
            return path;
        }

        // Use the first primary symbol
        CtxNode primarySymbol = symbols.getFirst();
        StringBuilder footprint = new StringBuilder();
        footprint.append(primarySymbol.getSymbolName() != null ? primarySymbol.getSymbolName() : "");

        // Find inheritance context for this symbol's path
        List<CtxEdge> edges = edgesBySourcePath.getOrDefault(path, List.of());
        List<String> inheritanceParts = edges.stream()
                .filter(e -> e.getTargetNode() != null && e.getTargetNode().getSymbolName() != null)
                .map(e -> {
                    RelationType type = e.getRelationType();
                    String rel = type != null ? type.toLowerCase() : "unknown";
                    return rel + " " + e.getTargetNode().getSymbolName();
                })
                .collect(Collectors.toList());

        // Parse decorators/annotations from metadata JSONB
        List<String> decorators = parseDecorators(primarySymbol.getMetadata());

        StringBuilder annotation = new StringBuilder();
        if (!inheritanceParts.isEmpty()) {
            annotation.append(" : ").append(String.join(", ", inheritanceParts));
        }
        for (String decorator : decorators) {
            annotation.append(" @").append(decorator);
        }

        if (!footprint.isEmpty() || !annotation.isEmpty()) {
            return path + " (" + footprint + annotation + ")";
        }
        return path;
    }

    private List<String> parseDecorators(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(metadata);
            List<String> result = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode decorators = root.get("decorators");
            if (decorators == null) {
                decorators = root.get("annotations");
            }
            if (decorators != null && decorators.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode d : decorators) {
                    if (d.isTextual()) {
                        result.add(d.asText());
                    } else if (d.has("name")) {
                        result.add(d.get("name").asText());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            logger.debug("Failed to parse metadata for decorators: {}", e.getMessage());
            return List.of();
        }
    }
}
