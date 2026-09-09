package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.DimensionStatDto;
import com.kratisai.controlplane.api.restdto.IngestionBatchStatsDto;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
@SlowTest
class IngestionStatsServiceTest {

    @Autowired
    private IngestionStatsService ingestionStatsService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;
    private Repository repository;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        team = ctx.team();
        repository = ctx.repository();
        batch = ctx.batch();
        batch.setActive(true);
        batch.setStartedAt(Instant.now().minusSeconds(300));
        batch.setCompletedAt(Instant.now());
        batch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(batch);
    }

    @Test
    void shouldReturnStatsForCompletedBatch() {
        // Create nodes with different types
        CtxNode funcNode = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/main.py");
        funcNode.setSymbolName("myFunction");
        CtxNode classNode = new CtxNode(batch, team.getId(), repository.getName(), NodeType.CLASS, "src/main.py");
        classNode.setSymbolName("MyClass");
        CtxNode anotherFuncNode =
                new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/utils.py");
        anotherFuncNode.setSymbolName("helperFunction");

        ctxNodeRepository.saveAllAndFlush(List.of(funcNode, classNode, anotherFuncNode));

        // Create edges with different types
        CtxEdge callEdge = new CtxEdge(batch, team.getId(), funcNode, classNode, RelationType.CALLS);
        CtxEdge importEdge = new CtxEdge(batch, team.getId(), anotherFuncNode, funcNode, RelationType.IMPORTS);

        ctxEdgeRepository.saveAllAndFlush(List.of(callEdge, importEdge));

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        IngestionBatchStatsDto stats = statsOpt.get();

        assertThat(stats.getBatchId()).isEqualTo(batch.getId().toString());
        assertThat(stats.getTotalNodes()).isEqualTo(3);
        assertThat(stats.getTotalEdges()).isEqualTo(2);
        assertThat(stats.getTotalDurationSeconds()).isNotNull();
        assertThat(stats.getTotalDurationSeconds()).isGreaterThanOrEqualTo(295);
        assertThat(stats.getTotalDurationSeconds()).isLessThanOrEqualTo(310);

        // Verify node type counts
        Map<NodeType, Integer> nodeTypeCounts = stats.getNodeTypeCounts();
        assertThat(nodeTypeCounts).containsEntry(NodeType.FUNCTION, 2);
        assertThat(nodeTypeCounts).containsEntry(NodeType.CLASS, 1);

        // Verify edge type counts
        Map<RelationType, Integer> edgeTypeCounts = stats.getEdgeTypeCounts();
        assertThat(edgeTypeCounts).containsEntry(RelationType.CALLS, 1);
        assertThat(edgeTypeCounts).containsEntry(RelationType.IMPORTS, 1);
    }

    @Test
    void shouldReturnEmptyForNonExistentBatch() {
        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(java.util.UUID.randomUUID());

        assertThat(statsOpt).isEmpty();
    }

    @Test
    void shouldReturnNullDurationForIncompleteBatch() {
        IngestionBatch incompleteBatch = new IngestionBatch(repository);
        incompleteBatch.setStartedAt(Instant.now());
        incompleteBatch.setCompletedAt(null);
        incompleteBatch.setStatus(IngestionStatus.PROCESSING);
        incompleteBatch = ingestionBatchRepository.saveAndFlush(incompleteBatch);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(incompleteBatch.getId());

        assertThat(statsOpt).isPresent();
        assertThat(statsOpt.get().getTotalDurationSeconds()).isNull();
    }

    @Test
    void shouldReturnZeroCountsForBatchWithNoNodesOrEdges() {
        IngestionBatch emptyBatch = new IngestionBatch(repository);
        emptyBatch.setStartedAt(Instant.now().minusSeconds(60));
        emptyBatch.setCompletedAt(Instant.now());
        emptyBatch.setStatus(IngestionStatus.SUCCESS);
        emptyBatch = ingestionBatchRepository.saveAndFlush(emptyBatch);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(emptyBatch.getId());

        assertThat(statsOpt).isPresent();
        assertThat(statsOpt.get().getTotalNodes()).isEqualTo(0);
        assertThat(statsOpt.get().getTotalEdges()).isEqualTo(0);
        assertThat(statsOpt.get().getNodeTypeCounts()).isEmpty();
        assertThat(statsOpt.get().getEdgeTypeCounts()).isEmpty();
    }

    @Test
    void shouldReturnLatestBatchStats() {
        // Create an older batch
        IngestionBatch oldBatch = new IngestionBatch(repository);
        oldBatch.setStartedAt(Instant.now().minusSeconds(600));
        oldBatch.setCompletedAt(Instant.now().minusSeconds(500));
        oldBatch.setStatus(IngestionStatus.SUCCESS);
        ingestionBatchRepository.saveAndFlush(oldBatch);

        // Current batch is newer (created in setUp)
        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getLatestBatchStats(repository.getId());

        assertThat(statsOpt).isPresent();
        assertThat(statsOpt.get().getBatchId()).isEqualTo(batch.getId().toString());
    }

    @Test
    void shouldReturnArchitecturePatternsInStats() {
        CtxArchitecturePattern pattern1 =
                new CtxArchitecturePattern(batch, team.getId(), "Hexagonal", "Hexagonal Architecture", List.of());
        CtxArchitecturePattern pattern2 =
                new CtxArchitecturePattern(batch, team.getId(), "MVC", "MVC Architecture", List.of());
        ctxArchitecturePatternRepository.saveAllAndFlush(List.of(pattern1, pattern2));

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        IngestionBatchStatsDto stats = statsOpt.get();
        assertThat(stats.getArchitecturePatterns()).hasSize(2);
        assertThat(stats.getArchitecturePatterns().get(0).name()).isEqualTo("Hexagonal");
        assertThat(stats.getArchitecturePatterns().get(0).description()).isEqualTo("Hexagonal Architecture");
        assertThat(stats.getArchitecturePatterns().get(1).name()).isEqualTo("MVC");
        assertThat(stats.getArchitecturePatterns().get(1).description()).isEqualTo("MVC Architecture");
    }

    @Test
    void shouldReturnEmptyArchitecturePatternListWhenNoneExist() {
        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        assertThat(statsOpt.get().getArchitecturePatterns()).isEmpty();
    }

    @Test
    void shouldReturnArchitecturePatternsSortedAlphabeticallyByName() {
        CtxArchitecturePattern patternZ =
                new CtxArchitecturePattern(batch, team.getId(), "Strangler", "Strangler Fig Pattern", List.of());
        CtxArchitecturePattern patternA =
                new CtxArchitecturePattern(batch, team.getId(), "CQRS", "CQRS Pattern", List.of());
        CtxArchitecturePattern patternM =
                new CtxArchitecturePattern(batch, team.getId(), "Repository", "Repository Pattern", List.of());
        ctxArchitecturePatternRepository.saveAllAndFlush(List.of(patternZ, patternA, patternM));

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        assertThat(statsOpt.get().getArchitecturePatterns()).hasSize(3);
        assertThat(statsOpt.get().getArchitecturePatterns().get(0).name()).isEqualTo("CQRS");
        assertThat(statsOpt.get().getArchitecturePatterns().get(1).name()).isEqualTo("Repository");
        assertThat(statsOpt.get().getArchitecturePatterns().get(2).name()).isEqualTo("Strangler");
    }

    @Test
    void shouldNotReturnArchitecturePatternsFromOtherBatches() {
        IngestionBatch otherBatch = new IngestionBatch(repository);
        otherBatch.setStatus(IngestionStatus.SUCCESS);
        otherBatch = ingestionBatchRepository.saveAndFlush(otherBatch);

        CtxArchitecturePattern otherPattern = new CtxArchitecturePattern(
                otherBatch, team.getId(), "Event Sourcing", "Event Sourcing Pattern", List.of());
        ctxArchitecturePatternRepository.saveAndFlush(otherPattern);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        assertThat(statsOpt.get().getArchitecturePatterns()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoBatchesExistForRepository() {
        // Create a repository without any batches by using the factory and then deleting the batch
        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext("Another Team", "another-repo");
        Repository anotherRepo = ctx.repository();

        // Delete the batch that was created by the factory
        ingestionBatchRepository.deleteAll(ingestionBatchRepository.findByRepositoryId(anotherRepo.getId()));
        ingestionBatchRepository.flush();

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getLatestBatchStats(anotherRepo.getId());

        assertThat(statsOpt).isEmpty();
    }

    @Test
    void shouldReturnDimensionStatsWithCorrectFileCounts() {
        // Create nodes
        CtxNode node1 = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/main.py");
        node1.setSymbolName("myFunction");
        CtxNode node2 = new CtxNode(batch, team.getId(), repository.getName(), NodeType.CLASS, "src/main.py");
        node2.setSymbolName("MyClass");
        CtxNode node3 = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/utils.py");
        node3.setSymbolName("helperFunction");
        ctxNodeRepository.saveAllAndFlush(List.of(node1, node2, node3));

        // Create dimensions
        CtxDimension dim1 = new CtxDimension(
                batch, team.getId(), DimensionCategory.DOMAIN, "Core Domain", "Core domain logic", List.of());
        CtxDimension dim2 = new CtxDimension(
                batch, team.getId(), DimensionCategory.CROSS_CUTTING, "Utilities", "Utility functions", List.of());
        ctxDimensionRepository.saveAllAndFlush(List.of(dim1, dim2));

        // Link nodes to dimensions
        CtxNodeDimension nd1 = new CtxNodeDimension(node1, dim1, 0.9);
        CtxNodeDimension nd2 = new CtxNodeDimension(node2, dim1, 0.8);
        CtxNodeDimension nd3 = new CtxNodeDimension(node3, dim2, 0.95);
        ctxNodeDimensionRepository.saveAllAndFlush(List.of(nd1, nd2, nd3));

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        IngestionBatchStatsDto stats = statsOpt.get();

        assertThat(stats.getDimensions()).hasSize(2);

        DimensionStatDto coreDomainStat = stats.getDimensions().stream()
                .filter(d -> "Core Domain".equals(d.name()))
                .findFirst()
                .orElseThrow();
        assertThat(coreDomainStat.category()).isEqualTo(DimensionCategory.DOMAIN);
        assertThat(coreDomainStat.fileCount()).isEqualTo(2);

        DimensionStatDto utilitiesStat = stats.getDimensions().stream()
                .filter(d -> "Utilities".equals(d.name()))
                .findFirst()
                .orElseThrow();
        assertThat(utilitiesStat.category()).isEqualTo(DimensionCategory.CROSS_CUTTING);
        assertThat(utilitiesStat.fileCount()).isEqualTo(1);
    }

    @Test
    void shouldReturnDimensionSynopsisInStats() {
        CtxDimension dim = new CtxDimension(
                batch, team.getId(), DimensionCategory.DOMAIN, "Core Domain", "Handles core domain logic", List.of());
        ctxDimensionRepository.saveAndFlush(dim);

        CtxNode node = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/main.py");
        ctxNodeRepository.saveAndFlush(node);
        ctxNodeDimensionRepository.saveAndFlush(new CtxNodeDimension(node, dim, 0.5));

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        DimensionStatDto coreDomainStat = statsOpt.get().getDimensions().stream()
                .filter(d -> "Core Domain".equals(d.name()))
                .findFirst()
                .orElseThrow();
        assertThat(coreDomainStat.synopsis()).isEqualTo("Handles core domain logic");
    }

    @Test
    void shouldReturnTopFivePathsOrderedByRankScoreDescending() {
        CtxDimension dim = new CtxDimension(
                batch, team.getId(), DimensionCategory.DOMAIN, "Core Domain", "Core domain logic", List.of());
        ctxDimensionRepository.saveAndFlush(dim);

        // Six distinct files with descending rank scores; only the top 5 should be returned.
        List<CtxNode> nodes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            nodes.add(
                    new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/file" + i + ".py"));
        }
        ctxNodeRepository.saveAllAndFlush(nodes);

        List<CtxNodeDimension> links = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // file0 has the highest rank score, file5 the lowest
            links.add(new CtxNodeDimension(nodes.get(i), dim, 1.0 - (i * 0.1)));
        }
        ctxNodeDimensionRepository.saveAllAndFlush(links);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        DimensionStatDto coreDomainStat = statsOpt.get().getDimensions().stream()
                .filter(d -> "Core Domain".equals(d.name()))
                .findFirst()
                .orElseThrow();

        assertThat(coreDomainStat.topFiles()).hasSize(5);
        assertThat(coreDomainStat.topFiles())
                .containsExactly("src/file0.py", "src/file1.py", "src/file2.py", "src/file3.py", "src/file4.py");
        assertThat(coreDomainStat.topFiles()).doesNotContain("src/file5.py");
    }

    @Test
    void shouldDeduplicateFilePathsWithMultipleNodesInTopFiles() {
        CtxDimension dim = new CtxDimension(
                batch, team.getId(), DimensionCategory.DOMAIN, "Core Domain", "Core domain logic", List.of());
        ctxDimensionRepository.saveAndFlush(dim);

        // Two nodes (function + class) share the same file path.
        CtxNode func = new CtxNode(batch, team.getId(), repository.getName(), NodeType.FUNCTION, "src/main.py");
        CtxNode clazz = new CtxNode(batch, team.getId(), repository.getName(), NodeType.CLASS, "src/main.py");
        ctxNodeRepository.saveAllAndFlush(List.of(func, clazz));

        ctxNodeDimensionRepository.saveAllAndFlush(
                List.of(new CtxNodeDimension(func, dim, 0.9), new CtxNodeDimension(clazz, dim, 0.7)));

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        DimensionStatDto coreDomainStat = statsOpt.get().getDimensions().stream()
                .filter(d -> "Core Domain".equals(d.name()))
                .findFirst()
                .orElseThrow();

        assertThat(coreDomainStat.topFiles()).containsExactly("src/main.py");
    }

    @Test
    void shouldReturnEmptyTopFilesWhenDimensionHasNoLinkedNodes() {
        CtxDimension dim = new CtxDimension(
                batch, team.getId(), DimensionCategory.CROSS_CUTTING, "Orphan Dimension", "No nodes linked", List.of());
        ctxDimensionRepository.saveAndFlush(dim);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        // No CtxNodeDimension rows exist for this dimension, so it won't appear in the
        // count-based dimensions list at all (it's driven by the join), which is existing behavior.
        assertThat(statsOpt.get().getDimensions()).noneMatch(d -> "Orphan Dimension".equals(d.name()));
    }

    @Test
    void shouldReturnUsageStatsWhenUsageIsRecorded() {
        batch.getUsage().apply(new LlmUsageSnapshot(0.0123, 1500L, 1000L, 500L));
        batch.setTotalToolCalls(42L);
        ingestionBatchRepository.saveAndFlush(batch);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        IngestionBatchStatsDto stats = statsOpt.get();
        assertThat(stats.getTotalSpend()).isEqualTo(0.0123);
        assertThat(stats.getTotalTokens()).isEqualTo(1500L);
        assertThat(stats.getPromptTokens()).isEqualTo(1000L);
        assertThat(stats.getCompletionTokens()).isEqualTo(500L);
        assertThat(stats.getTotalToolCalls()).isEqualTo(42L);
        assertThat(stats.getUsageLastUpdatedAt()).isNotNull();
    }

    @Test
    void shouldReturnNullUsageFieldsWhenNoUsageRecorded() {
        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getBatchStats(batch.getId());

        assertThat(statsOpt).isPresent();
        IngestionBatchStatsDto stats = statsOpt.get();
        assertThat(stats.getTotalSpend()).isNull();
        assertThat(stats.getTotalTokens()).isNull();
        assertThat(stats.getPromptTokens()).isNull();
        assertThat(stats.getCompletionTokens()).isNull();
        assertThat(stats.getTotalToolCalls()).isNull();
        assertThat(stats.getUsageLastUpdatedAt()).isNull();
    }

    @Test
    void shouldReturnUsageStatsViaLatestBatchEndpoint() {
        batch.getUsage().apply(new LlmUsageSnapshot(0.25, 4200L, 3000L, 1200L));
        batch.setTotalToolCalls(7L);
        ingestionBatchRepository.saveAndFlush(batch);

        Optional<IngestionBatchStatsDto> statsOpt = ingestionStatsService.getLatestBatchStats(repository.getId());

        assertThat(statsOpt).isPresent();
        IngestionBatchStatsDto stats = statsOpt.get();
        assertThat(stats.getTotalSpend()).isEqualTo(0.25);
        assertThat(stats.getTotalTokens()).isEqualTo(4200L);
        assertThat(stats.getPromptTokens()).isEqualTo(3000L);
        assertThat(stats.getCompletionTokens()).isEqualTo(1200L);
        assertThat(stats.getTotalToolCalls()).isEqualTo(7L);
        assertThat(stats.getUsageLastUpdatedAt()).isNotNull();
    }
}
