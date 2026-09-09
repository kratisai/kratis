package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.ArchitecturePatternDto;
import com.kratisai.controlplane.api.restdto.DimensionStatDto;
import com.kratisai.controlplane.api.restdto.IngestionBatchStatsDto;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionStatsService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionStatsService.class);
    private static final int TOP_FILES_PER_DIMENSION = 5;

    private final IngestionBatchRepository ingestionBatchRepository;
    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final CtxArchitecturePatternRepository ctxArchitecturePatternRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxNodeDimensionRepository ctxNodeDimensionRepository;
    private final CtxDimensionRepository ctxDimensionRepository;

    public IngestionStatsService(
            IngestionBatchRepository ingestionBatchRepository,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            CtxDimensionRepository ctxDimensionRepository) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ctxDimensionRepository = ctxDimensionRepository;
    }

    @Transactional(readOnly = true)
    public Optional<IngestionBatchStatsDto> getBatchStats(UUID batchId) {
        logger.debug("Fetching stats for batch {}", batchId);

        Optional<IngestionBatch> batchOpt = ingestionBatchRepository.findById(batchId);
        if (batchOpt.isEmpty()) {
            logger.warn("Batch {} not found", batchId);
            return Optional.empty();
        }

        IngestionBatch batch = batchOpt.get();

        // Calculate duration
        Long totalDurationSeconds = null;
        if (batch.getStartedAt() != null && batch.getCompletedAt() != null) {
            totalDurationSeconds = batch.getCompletedAt().getEpochSecond()
                    - batch.getStartedAt().getEpochSecond();
        }

        // Get node counts by type
        List<Object[]> nodeCounts = ctxNodeRepository.countNodesByType(batchId);
        Map<NodeType, Integer> nodeTypeCounts = new HashMap<>();
        for (Object[] row : nodeCounts) {
            NodeType nodeType = (NodeType) row[0];
            Long count = (Long) row[1];
            nodeTypeCounts.put(nodeType, count.intValue());
        }

        // Get edge counts by type
        List<Object[]> edgeCounts = ctxEdgeRepository.countEdgesByType(batchId);
        Map<RelationType, Integer> edgeTypeCounts = new HashMap<>();
        for (Object[] row : edgeCounts) {
            RelationType relationType = (RelationType) row[0];
            Long count = (Long) row[1];
            edgeTypeCounts.put(relationType, count.intValue());
        }

        // Get totals
        int totalNodes =
                nodeTypeCounts.values().stream().mapToInt(Integer::intValue).sum();
        int totalEdges =
                edgeTypeCounts.values().stream().mapToInt(Integer::intValue).sum();

        // Get archetype, and wiki page stats
        List<String> wikiPageSlugs = ctxWikiPageRepository.findByBatchId(batchId).stream()
                .map(CtxWikiPage::getPageSlug)
                .collect(Collectors.toList());
        List<ArchitecturePatternDto> architecturePatterns =
                ctxArchitecturePatternRepository.findByBatchId(batchId).stream()
                        .map(pattern -> new ArchitecturePatternDto(pattern.getName(), pattern.getDescription()))
                        .sorted(Comparator.comparing(ArchitecturePatternDto::name))
                        .collect(Collectors.toList());

        // Get LiteLLM usage stats recorded when the ingestion completed
        LlmUsage usage = batch.getUsage();
        Double totalSpend = null;
        Long totalTokens = null;
        Long promptTokens = null;
        Long completionTokens = null;
        Long totalToolCalls = null;
        Instant usageLastUpdatedAt = usage.getUsageLastUpdatedAt();
        if (usageLastUpdatedAt != null) {
            totalSpend = usage.getTotalSpend();
            totalTokens = usage.getTotalTokens();
            promptTokens = usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens();
        }
        if (batch.getTotalToolCalls() != null) {
            totalToolCalls = batch.getTotalToolCalls();
        }

        // Get dimension stats via optimized GROUP BY query
        List<Object[]> dimensionCounts = ctxNodeDimensionRepository.countNodesByDimension(batchId);
        Map<String, List<String>> topFilesByDimension = computeTopFilesByDimension(batchId);

        List<DimensionStatDto> dimensions = new ArrayList<>();
        for (Object[] row : dimensionCounts) {
            String name = (String) row[0];
            DimensionCategory category = (DimensionCategory) row[1];
            String synopsis = (String) row[2];
            Long count = (Long) row[3];
            List<String> topFiles = topFilesByDimension.getOrDefault(dimensionKey(name, category), List.of());
            dimensions.add(new DimensionStatDto(category, name, count.intValue(), synopsis, topFiles));
        }

        IngestionBatchStatsDto dto = new IngestionBatchStatsDto(
                batchId.toString(),
                totalDurationSeconds,
                totalNodes,
                totalEdges,
                nodeTypeCounts,
                edgeTypeCounts,
                dimensions,
                architecturePatterns,
                wikiPageSlugs,
                totalSpend,
                totalTokens,
                promptTokens,
                completionTokens,
                totalToolCalls,
                usageLastUpdatedAt);

        logger.trace("Ingestion batch has dimensions {}", ctxDimensionRepository.findByBatchId(batchId));

        return Optional.of(dto);
    }

    private Map<String, List<String>> computeTopFilesByDimension(UUID batchId) {
        List<Object[]> rankedFiles = ctxNodeDimensionRepository.findRankedFilePathsByDimension(batchId);
        Map<String, List<String>> topFilesByDimension = new LinkedHashMap<>();
        for (Object[] row : rankedFiles) {
            String name = (String) row[0];
            DimensionCategory category = (DimensionCategory) row[1];
            String path = (String) row[2];
            if (path == null) {
                continue;
            }
            List<String> paths =
                    topFilesByDimension.computeIfAbsent(dimensionKey(name, category), key -> new ArrayList<>());
            if (paths.size() < TOP_FILES_PER_DIMENSION && !paths.contains(path)) {
                paths.add(path);
            }
        }
        return topFilesByDimension;
    }

    private String dimensionKey(String name, DimensionCategory category) {
        return category + "::" + name;
    }

    @Transactional(readOnly = true)
    public Optional<IngestionBatchStatsDto> getLatestBatchStats(UUID repositoryId) {
        logger.debug("Fetching stats for latest batch of repository {}", repositoryId);

        Optional<IngestionBatch> latestBatch =
                ingestionBatchRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repositoryId);

        if (latestBatch.isEmpty()) {
            logger.warn("No batches found for repository {}", repositoryId);
            return Optional.empty();
        }

        return getBatchStats(latestBatch.get().getId());
    }
}
