package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
class CtxDimensionRepositoryTest {

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    private IngestionBatch batch;
    private UUID teamId;

    @BeforeEach
    void setUp() {
        Team team = new Team("Test Team", "test-team");
        team = teamRepository.save(team);
        teamId = team.getId();

        Repository repo = new Repository();
        repo.setName("test-repo");
        repo.setUrl("https://example.com/repo.git");
        repo.setBranch("main");
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        batch = new IngestionBatch(repo);
        batch = ingestionBatchRepository.save(batch);
    }

    @Test
    @Transactional
    void shouldSaveAndFindDimension() {
        CtxDimension dimension = new CtxDimension(
                batch,
                teamId,
                DimensionCategory.DOMAIN,
                "Booking Management",
                "Handles all booking operations",
                List.of("**/booking/**", "*Booking*.java"));

        CtxDimension saved = ctxDimensionRepository.save(dimension);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCategory()).isEqualTo(DimensionCategory.DOMAIN);
        assertThat(saved.getName()).isEqualTo("Booking Management");
        assertThat(saved.getGlobPatterns()).hasSize(2);

        List<CtxDimension> found = ctxDimensionRepository.findByBatchId(batch.getId());
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getName()).isEqualTo("Booking Management");
    }

    @Test
    @Transactional
    void shouldDeleteByBatchId() {
        CtxDimension dimension = new CtxDimension(
                batch,
                teamId,
                DimensionCategory.ARCHETYPE,
                "Controller",
                "Web controllers",
                List.of("*Controller.java"));
        ctxDimensionRepository.save(dimension);

        ctxDimensionRepository.deleteByBatchId(batch.getId());

        List<CtxDimension> found = ctxDimensionRepository.findByBatchId(batch.getId());
        assertThat(found).isEmpty();
    }

    @Test
    @Transactional
    void shouldSaveAndFindNodeDimension() {
        CtxNode node = new CtxNode(batch, teamId, "test-repo", NodeType.FILE, "src/BookingService.java");
        node = ctxNodeRepository.save(node);

        CtxDimension dimension = new CtxDimension(
                batch,
                teamId,
                DimensionCategory.DOMAIN,
                "Booking Management",
                "Handles all booking operations",
                List.of("**/booking/**"));
        dimension = ctxDimensionRepository.save(dimension);

        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dimension, 0.85);
        CtxNodeDimension saved = ctxNodeDimensionRepository.save(nodeDimension);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRankScore()).isEqualTo(0.85);

        List<CtxNodeDimension> byDimension = ctxNodeDimensionRepository.findByDimensionId(dimension.getId());
        assertThat(byDimension).hasSize(1);
        assertThat(byDimension.getFirst().getNode().getId()).isEqualTo(node.getId());

        List<CtxNodeDimension> byNode = ctxNodeDimensionRepository.findByNodeId(node.getId());
        assertThat(byNode).hasSize(1);
        assertThat(byNode.getFirst().getDimension().getId()).isEqualTo(dimension.getId());
    }

    @Test
    @Transactional
    void shouldDeleteNodeDimensionByBatchId() {
        CtxNode node = new CtxNode(batch, teamId, "test-repo", NodeType.FILE, "src/BookingService.java");
        node = ctxNodeRepository.save(node);

        CtxDimension dimension = new CtxDimension(
                batch,
                teamId,
                DimensionCategory.DOMAIN,
                "Booking Management",
                "Handles all booking operations",
                List.of("**/booking/**"));
        dimension = ctxDimensionRepository.save(dimension);

        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dimension, 0.85);
        ctxNodeDimensionRepository.save(nodeDimension);

        ctxNodeDimensionRepository.deleteByBatchId(batch.getId());

        List<CtxNodeDimension> byNode = ctxNodeDimensionRepository.findByNodeId(node.getId());
        assertThat(byNode).isEmpty();
    }
}
