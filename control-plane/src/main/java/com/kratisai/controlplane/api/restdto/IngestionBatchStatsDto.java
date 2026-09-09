package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class IngestionBatchStatsDto {

    private String batchId;
    private Long totalDurationSeconds;
    private int totalNodes;
    private int totalEdges;
    private Map<NodeType, Integer> nodeTypeCounts;
    private Map<RelationType, Integer> edgeTypeCounts;
    private List<DimensionStatDto> dimensions;
    private List<ArchitecturePatternDto> architecturePatterns;
    private List<String> wikiPageSlugs;
    private Double totalSpend;
    private Long totalTokens;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalToolCalls;
    private Instant usageLastUpdatedAt;

    public IngestionBatchStatsDto(
            String batchId,
            Long totalDurationSeconds,
            int totalNodes,
            int totalEdges,
            Map<NodeType, Integer> nodeTypeCounts,
            Map<RelationType, Integer> edgeTypeCounts,
            List<DimensionStatDto> dimensions,
            List<ArchitecturePatternDto> architecturePatterns,
            List<String> wikiPageSlugs,
            Double totalSpend,
            Long totalTokens,
            Long promptTokens,
            Long completionTokens,
            Long totalToolCalls,
            Instant usageLastUpdatedAt) {
        this.batchId = batchId;
        this.totalDurationSeconds = totalDurationSeconds;
        this.totalNodes = totalNodes;
        this.totalEdges = totalEdges;
        this.nodeTypeCounts = nodeTypeCounts;
        this.edgeTypeCounts = edgeTypeCounts;
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions is required");
        this.architecturePatterns = Objects.requireNonNull(architecturePatterns, "architecturePatterns is required");
        this.wikiPageSlugs = Objects.requireNonNull(wikiPageSlugs, "wikiPageSlugs is required");
        this.totalSpend = totalSpend;
        this.totalTokens = totalTokens;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalToolCalls = totalToolCalls;
        this.usageLastUpdatedAt = usageLastUpdatedAt;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Long getTotalDurationSeconds() {
        return totalDurationSeconds;
    }

    public void setTotalDurationSeconds(Long totalDurationSeconds) {
        this.totalDurationSeconds = totalDurationSeconds;
    }

    public int getTotalNodes() {
        return totalNodes;
    }

    public void setTotalNodes(int totalNodes) {
        this.totalNodes = totalNodes;
    }

    public int getTotalEdges() {
        return totalEdges;
    }

    public void setTotalEdges(int totalEdges) {
        this.totalEdges = totalEdges;
    }

    public Map<NodeType, Integer> getNodeTypeCounts() {
        return nodeTypeCounts;
    }

    public void setNodeTypeCounts(Map<NodeType, Integer> nodeTypeCounts) {
        this.nodeTypeCounts = nodeTypeCounts;
    }

    public Map<RelationType, Integer> getEdgeTypeCounts() {
        return edgeTypeCounts;
    }

    public void setEdgeTypeCounts(Map<RelationType, Integer> edgeTypeCounts) {
        this.edgeTypeCounts = edgeTypeCounts;
    }

    public List<DimensionStatDto> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<DimensionStatDto> dimensions) {
        this.dimensions = dimensions;
    }

    public List<String> getWikiPageSlugs() {
        return wikiPageSlugs;
    }

    public void setWikiPageSlugs(List<String> wikiPageSlugs) {
        this.wikiPageSlugs = wikiPageSlugs;
    }

    public List<ArchitecturePatternDto> getArchitecturePatterns() {
        return architecturePatterns;
    }

    public void setArchitecturePatterns(List<ArchitecturePatternDto> architecturePatterns) {
        this.architecturePatterns = architecturePatterns;
    }

    public Double getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(Double totalSpend) {
        this.totalSpend = totalSpend;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getTotalToolCalls() {
        return totalToolCalls;
    }

    public void setTotalToolCalls(Long totalToolCalls) {
        this.totalToolCalls = totalToolCalls;
    }

    public Instant getUsageLastUpdatedAt() {
        return usageLastUpdatedAt;
    }

    public void setUsageLastUpdatedAt(Instant usageLastUpdatedAt) {
        this.usageLastUpdatedAt = usageLastUpdatedAt;
    }
}
