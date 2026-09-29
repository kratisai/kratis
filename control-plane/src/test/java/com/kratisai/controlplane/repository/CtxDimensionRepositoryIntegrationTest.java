package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
public class CtxDimensionRepositoryIntegrationTest {

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private TestDataFactory.TestContext testContext;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        testContext = testDataFactory.createDefaultContext();
    }

    @Test
    void testCreateAndFindCtxDimension() {
        // Arrange
        CtxDimension dimension = new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.DOMAIN,
                "Test Domain",
                "A test domain dimension",
                List.of("src/main/java/**/*.java"));

        // Act
        CtxDimension savedDimension = ctxDimensionRepository.save(dimension);

        // Assert
        assertThat(savedDimension.getId()).isNotNull();
        assertThat(savedDimension.getCategory()).isEqualTo(DimensionCategory.DOMAIN);
        assertThat(savedDimension.getName()).isEqualTo("Test Domain");
        assertThat(savedDimension.getSynopsis()).isEqualTo("A test domain dimension");
        assertThat(savedDimension.getGlobPatterns()).containsExactly("src/main/java/**/*.java");

        List<CtxDimension> foundDimensions =
                ctxDimensionRepository.findByBatchId(testContext.batch().getId());
        assertThat(foundDimensions).hasSize(1);
        assertThat(foundDimensions.getFirst().getId()).isEqualTo(savedDimension.getId());
    }

    @Test
    void testCreateAndFindCtxNodeDimension() {
        // Arrange
        CtxDimension dimension = new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.ARCHETYPE,
                "Test Archetype",
                "A test archetype dimension",
                List.of("src/**/*.ts"));
        dimension = ctxDimensionRepository.save(dimension);

        CtxNode node = new CtxNode(
                testContext.batch(),
                testContext.team().getId(),
                testContext.repository().getName(),
                NodeType.FILE,
                "src/main.ts");
        node = ctxNodeRepository.save(node);

        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dimension, 0.95);

        // Act
        CtxNodeDimension savedNodeDimension = ctxNodeDimensionRepository.save(nodeDimension);

        // Assert
        assertThat(savedNodeDimension.getId()).isNotNull();
        assertThat(savedNodeDimension.getRankScore()).isEqualTo(0.95);

        List<CtxNodeDimension> foundByDimension = ctxNodeDimensionRepository.findByDimensionId(dimension.getId());
        assertThat(foundByDimension).hasSize(1);
        assertThat(foundByDimension.getFirst().getNode().getId()).isEqualTo(node.getId());

        List<CtxNodeDimension> foundByNode = ctxNodeDimensionRepository.findByNodeId(node.getId());
        assertThat(foundByNode).hasSize(1);
        assertThat(foundByNode.getFirst().getDimension().getId()).isEqualTo(dimension.getId());
    }

    @Test
    void testFindByDimensionIdIn() {
        // Arrange
        CtxDimension dim1 = ctxDimensionRepository.save(new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.CROSS_CUTTING,
                "Dim 1",
                "Synopsis 1",
                List.of("**/*.java")));

        CtxDimension dim2 = ctxDimensionRepository.save(new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.CROSS_CUTTING,
                "Dim 2",
                "Synopsis 2",
                List.of("**/*.ts")));

        CtxNode node = ctxNodeRepository.save(new CtxNode(
                testContext.batch(),
                testContext.team().getId(),
                testContext.repository().getName(),
                NodeType.FILE,
                "src/test.java"));

        ctxNodeDimensionRepository.save(new CtxNodeDimension(node, dim1, 0.8));
        ctxNodeDimensionRepository.save(new CtxNodeDimension(node, dim2, 0.9));

        // Act
        List<CtxNodeDimension> found =
                ctxNodeDimensionRepository.findByDimensionIdIn(List.of(dim1.getId(), dim2.getId()));

        // Assert
        assertThat(found).hasSize(2);
        assertThat(found).extracting("dimension.id").containsExactlyInAnyOrder(dim1.getId(), dim2.getId());
    }

    @Test
    void testFindByNodeIdWithDimension() {
        // Arrange
        CtxDimension dimension = ctxDimensionRepository.save(new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.DOMAIN,
                "Domain Dim",
                "Domain synopsis",
                List.of("**/*.py")));

        CtxNode node = ctxNodeRepository.save(new CtxNode(
                testContext.batch(),
                testContext.team().getId(),
                testContext.repository().getName(),
                NodeType.FILE,
                "src/test.py"));

        ctxNodeDimensionRepository.save(new CtxNodeDimension(node, dimension, 0.85));

        // Act
        List<CtxNodeDimension> found = ctxNodeDimensionRepository.findByNodeIdWithDimension(node.getId());

        // Assert
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getDimension().getName()).isEqualTo("Domain Dim");
        assertThat(found.getFirst().getDimension().getSynopsis()).isEqualTo("Domain synopsis");
    }

    @Test
    @Transactional
    void testDeleteByBatchIdForCtxDimension() {
        // Arrange
        ctxDimensionRepository.save(new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.ARCHETYPE,
                "To Be Deleted",
                "Will be deleted",
                List.of("**/*.go")));

        assertThat(ctxDimensionRepository.findByBatchId(testContext.batch().getId()))
                .hasSize(1);

        // Act
        ctxDimensionRepository.deleteByBatchId(testContext.batch().getId());

        // Assert
        assertThat(ctxDimensionRepository.findByBatchId(testContext.batch().getId()))
                .isEmpty();
    }

    @Test
    @Transactional
    void testDeleteByBatchIdForCtxNodeDimension() {
        // Arrange
        CtxDimension dimension = ctxDimensionRepository.save(new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.DOMAIN,
                "Domain",
                "Domain synopsis",
                List.of("**/*.rs")));

        CtxNode node = ctxNodeRepository.save(new CtxNode(
                testContext.batch(),
                testContext.team().getId(),
                testContext.repository().getName(),
                NodeType.FILE,
                "src/main.rs"));

        ctxNodeDimensionRepository.save(new CtxNodeDimension(node, dimension, 0.9));

        assertThat(ctxNodeDimensionRepository.findByNodeId(node.getId())).hasSize(1);

        // Act
        ctxNodeDimensionRepository.deleteByBatchId(testContext.batch().getId());

        // Assert
        assertThat(ctxNodeDimensionRepository.findByNodeId(node.getId())).isEmpty();
    }
}
