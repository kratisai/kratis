package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the repository-intelligence tools through the real database without a
 * surrounding transaction. The tools run on agent threads, so node associations
 * must be fetched eagerly or Hibernate throws a lazy-initialization error.
 */
@SpringIntegrationTest
class RepositoryIntelligenceToolsComponentTest {

    @Autowired
    private RepositoryIntelligenceTools repositoryIntelligenceTools;

    @Autowired
    private CtxDimensionRepository dimensionRepository;

    @Autowired
    private CtxNodeRepository nodeRepository;

    @Autowired
    private CtxNodeDimensionRepository nodeDimensionRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ObjectMapper objectMapper;

    private TestDataFactory.TestContext testContext;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        testContext = testDataFactory.createDefaultContext();
        toolContext = new ToolContext(Map.of("batchId", testContext.batch().getId()));
    }

    @Test
    void getDimensions_loadsNodePathsWithoutAnOpenSession() throws Exception {
        CtxDimension dimension = saveDimension("Booking");
        CtxNode node = saveNode("src/Booking.java", "Booking");
        nodeDimensionRepository.save(new CtxNodeDimension(node, dimension, 0.95));

        JsonNode result = objectMapper.readTree(repositoryIntelligenceTools.getDimensions(toolContext));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("name").asText()).isEqualTo("Booking");
        assertThat(result.get(0).path("topFiles").get(0).asText()).isEqualTo("src/Booking.java:Booking");
    }

    @Test
    void getDimensions_omitsSymbolWhenNodeHasNoSymbolName() throws Exception {
        CtxDimension dimension = saveDimension("Config");
        CtxNode node = saveNode("application.yml", null);
        nodeDimensionRepository.save(new CtxNodeDimension(node, dimension, 0.5));

        JsonNode result = objectMapper.readTree(repositoryIntelligenceTools.getDimensions(toolContext));

        assertThat(result.get(0).path("topFiles").get(0).asText()).isEqualTo("application.yml");
    }

    @Test
    void getDimensions_limitsToTopTenRankedFiles() throws Exception {
        CtxDimension dimension = saveDimension("Domain");
        for (int i = 0; i < 12; i++) {
            CtxNode node = saveNode("src/File" + i + ".java", "File" + i);
            nodeDimensionRepository.save(new CtxNodeDimension(node, dimension, i / 10.0));
        }

        JsonNode topFiles = objectMapper
                .readTree(repositoryIntelligenceTools.getDimensions(toolContext))
                .get(0)
                .path("topFiles");

        assertThat(topFiles).hasSize(10);
        assertThat(topFiles.get(0).asText()).isEqualTo("src/File11.java:File11");
    }

    @Test
    void getDimensions_returnsEmptyListWhenBatchHasNoDimensions() throws Exception {
        JsonNode result = objectMapper.readTree(repositoryIntelligenceTools.getDimensions(toolContext));

        assertThat(result).isEmpty();
    }

    private CtxDimension saveDimension(String name) {
        return dimensionRepository.save(new CtxDimension(
                testContext.batch(),
                testContext.team().getId(),
                DimensionCategory.DOMAIN,
                name,
                name + " synopsis",
                List.of("**/*.java")));
    }

    private CtxNode saveNode(String path, String symbolName) {
        CtxNode node = new CtxNode(
                testContext.batch(),
                testContext.team().getId(),
                testContext.repository().getName(),
                NodeType.FILE,
                path);
        node.setSymbolName(symbolName);
        return nodeRepository.save(node);
    }
}
