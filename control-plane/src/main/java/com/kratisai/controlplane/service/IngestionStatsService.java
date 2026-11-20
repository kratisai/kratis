package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.ArchitecturePatternDto;
import com.kratisai.controlplane.api.restdto.DimensionStatDto;
import com.kratisai.controlplane.api.restdto.IngestionBatchStatsDto;
import com.kratisai.controlplane.api.restdto.RelationSide;
import com.kratisai.controlplane.api.restdto.RelationStatDto;
import com.kratisai.controlplane.api.restdto.SurfaceStatDto;
import com.kratisai.controlplane.api.restdto.WikiPageDto;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationRationales;
import com.kratisai.controlplane.model.RelationType;
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
    private final CtxSurfaceRepository ctxSurfaceRepository;
    private final CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository;
    private final CtxRepoRelationRepository ctxRepoRelationRepository;
    private final ObjectMapper objectMapper;

    public IngestionStatsService(
            IngestionBatchRepository ingestionBatchRepository,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            CtxArchitecturePatternRepository ctxArchitecturePatternRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxNodeDimensionRepository ctxNodeDimensionRepository,
            CtxSurfaceRepository ctxSurfaceRepository,
            CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository,
            CtxRepoRelationRepository ctxRepoRelationRepository,
            ObjectMapper objectMapper) {
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.ctxArchitecturePatternRepository = ctxArchitecturePatternRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxNodeDimensionRepository = ctxNodeDimensionRepository;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
        this.ctxSurfaceAttributeRepository = ctxSurfaceAttributeRepository;
        this.ctxRepoRelationRepository = ctxRepoRelationRepository;
        this.objectMapper = objectMapper;
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

        // Get archetype, and wiki page stats ordered deterministically (orderIndex ASC, title ASC, pageSlug ASC)
        List<CtxWikiPage> wikiPagesList =
                ctxWikiPageRepository.findByBatchIdOrderByOrderIndexAscTitleAscPageSlugAsc(batchId);
        List<WikiPageDto> wikiPages = wikiSummaries(wikiPagesList);
        List<ArchitecturePatternDto> architecturePatterns =
                ctxArchitecturePatternRepository.findByBatchId(batchId).stream()
                        .map(pattern -> new ArchitecturePatternDto(pattern.getName(), pattern.getDescription()))
                        .sorted(Comparator.comparing(ArchitecturePatternDto::name))
                        .collect(Collectors.toList());

        // Get LiteLLM usage stats recorded when the ingestion completed
        List<IngestionModelUsage> modelUsage = batch.getModelUsage();
        Double totalSpend = null;
        Long totalTokens = null;
        Long promptTokens = null;
        Long completionTokens = null;
        Long totalToolCalls = null;
        Instant usageLastUpdatedAt = modelUsage.stream()
                .map(IngestionModelUsage::getUsageLastUpdatedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        if (usageLastUpdatedAt != null) {
            totalSpend = modelUsage.stream()
                    .mapToDouble(usage -> usage.getTotalSpend() != null ? usage.getTotalSpend() : 0.0)
                    .sum();
            totalTokens = modelUsage.stream()
                    .mapToLong(usage -> usage.getTotalTokens() != null ? usage.getTotalTokens() : 0L)
                    .sum();
            promptTokens = modelUsage.stream()
                    .mapToLong(usage -> usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L)
                    .sum();
            completionTokens = modelUsage.stream()
                    .mapToLong(usage -> usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L)
                    .sum();
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

        List<SurfaceStatDto> surfaces = getSurfaceStats(batchId);
        List<RelationStatDto> relations =
                getRelationStats(batchId, batch.getRepository().getId());

        IngestionBatchStatsDto dto = new IngestionBatchStatsDto(
                batchId.toString(),
                totalDurationSeconds,
                totalNodes,
                totalEdges,
                nodeTypeCounts,
                edgeTypeCounts,
                dimensions,
                architecturePatterns,
                surfaces,
                relations,
                wikiPages,
                totalSpend,
                totalTokens,
                promptTokens,
                completionTokens,
                totalToolCalls,
                usageLastUpdatedAt);

        logger.trace("getBatchStats: {}", dto);

        return Optional.of(dto);
    }

    private static List<WikiPageDto> wikiSummaries(List<CtxWikiPage> pages) {
        Map<UUID, Long> childCounts = pages.stream()
                .filter(page -> page.getParentPage() != null)
                .collect(Collectors.groupingBy(page -> page.getParentPage().getId(), Collectors.counting()));
        return pages.stream()
                .map(page -> new WikiPageDto(
                        page.getId(),
                        page.getRepoName(),
                        page.getParentPage() != null ? page.getParentPage().getId() : null,
                        page.getPageSlug(),
                        page.getTitle(),
                        page.getOrderIndex(),
                        null,
                        childCounts.getOrDefault(page.getId(), 0L) > 0))
                .toList();
    }

    private List<SurfaceStatDto> getSurfaceStats(UUID batchId) {
        Map<UUID, Integer> attributeCounts = new HashMap<>();
        for (Object[] row : ctxSurfaceAttributeRepository.countByBatchIdGroupedBySurface(batchId)) {
            attributeCounts.put((UUID) row[0], ((Long) row[1]).intValue());
        }

        return ctxSurfaceRepository.findByBatchId(batchId).stream()
                .map(surface -> new SurfaceStatDto(
                        surface.getRole(),
                        surface.getDescription(),
                        surface.getDetectedBy(),
                        attributeCounts.getOrDefault(surface.getId(), 0)))
                .sorted(Comparator.comparing(SurfaceStatDto::role).thenComparing(SurfaceStatDto::description))
                .toList();
    }

    private List<RelationStatDto> getRelationStats(UUID batchId, UUID currentRepositoryId) {
        return ctxRepoRelationRepository.findCurrentByBatchId(batchId).stream()
                .map(relation -> {
                    boolean currentIsA =
                            relation.getBatchA().getRepository().getId().equals(currentRepositoryId);
                    IngestionBatch otherBatch = currentIsA ? relation.getBatchB() : relation.getBatchA();
                    return new RelationStatDto(
                            relation.getNature(),
                            relationSide(relation.getDirection(), currentIsA),
                            relation.getConfidence(),
                            otherBatch.getRepository().getId(),
                            otherBatch.getRepository().getName(),
                            RelationRationales.extract(relation.getCitations(), relation.getId(), objectMapper));
                })
                .sorted(Comparator.comparing(RelationStatDto::otherRepositoryName)
                        .thenComparing(relation -> relation.nature().name()))
                .toList();
    }

    static RelationSide relationSide(Direction direction, boolean currentIsA) {
        return switch (direction) {
            case BIDIRECTIONAL -> RelationSide.BIDIRECTIONAL;
            case UNKNOWN -> RelationSide.UNKNOWN;
            case A_TO_B -> currentIsA ? RelationSide.UPSTREAM : RelationSide.DOWNSTREAM;
            case B_TO_A -> currentIsA ? RelationSide.DOWNSTREAM : RelationSide.UPSTREAM;
        };
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
