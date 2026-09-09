package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.*;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class DimensionDiscoveryServiceIntegrationTest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DimensionDiscoveryService dimensionDiscoveryService;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private FakeChatModel fakeChatModel;

    private Team team;
    private IngestionBatch batch;
    private CtxNode controllerNode;
    private CtxNode serviceNode;

    @BeforeEach
    void setUp() {
        fakeChatModel.reset();
        databaseCleaner.cleanAll();

        // Shot 1: Request additional context
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("If you lack sufficient context")
                .response("""
                                {
                                  "domains": [{"name": "User Management", "globPatterns": ["*User*.java"]}],
                                  "archetypes": [
                                      {"name": "Controller", "globPatterns": ["*Controller.java"]},
                                      {"name": "Service", "globPatterns": ["*Service.java"]}
                                  ],
                                  "crossCutting": [{"name": "Utils", "globPatterns": ["*util*.java"]}],
                                  "additionalContextRequest": {
                                    "ambiguousFilePaths": ["src/main/java/com/example/StringUtils.java"],
                                    "rationale": "Need to verify if this is a cross-cutting utility.",
                                    "certainDimensions": ["User Management", "Controller", "Service"],
                                    "expectedResolution": "Reading the file will confirm its utility nature."
                                  }
                                }
                                """)
                .build());

        // Shot 2: Finalize with confidence
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("You previously requested additional context")
                .response("""
                                {
                                  "domains": [{"name": "User Management", "globPatterns": ["*User*.java"]}],
                                  "archetypes": [
                                      {"name": "Controller", "globPatterns": ["*Controller.java"]},
                                      {"name": "Service", "globPatterns": ["*Service.java"]}
                                  ],
                                  "crossCutting": [{"name": "Utils", "globPatterns": ["*util*.java"]}],
                                  "additionalContextRequest": null
                                }
                                """)
                .build());

        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("Dimension Team", "test-repo");
        team = context.team();
        batch = context.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        controllerNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserController.java");
        serviceNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserService.java");
        CtxNode utilNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/StringUtils.java");

        ctxNodeRepository.saveAll(List.of(controllerNode, serviceNode, utilNode));
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void happyPath_discoversDimensionsAndTagsNodes() {
        // Execute
        dimensionDiscoveryService.discoverDimensions(batch);

        // Verify dimensions were created
        List<CtxDimension> dimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        assertThat(dimensions).isNotEmpty();

        // Verify node-dimension mappings were created
        List<CtxNodeDimension> nodeDimensions = ctxNodeDimensionRepository.findByNodeId(controllerNode.getId());
        assertThat(nodeDimensions).isNotEmpty();

        // Verify rankScore is initialized to 0.0
        for (CtxNodeDimension nd : nodeDimensions) {
            assertThat(nd.getRankScore()).isEqualTo(0.0);
        }
    }

    @Test
    void happyPath_propagatesDimensionsViaGraphEdges() {
        // Create an edge between controller and service
        CtxEdge edge = new CtxEdge(batch, team.getId(), controllerNode, serviceNode, RelationType.CALLS);
        ctxEdgeRepository.saveAndFlush(edge);

        // Execute
        dimensionDiscoveryService.discoverDimensions(batch);

        // Verify both nodes are tagged with the same dimension (via propagation)
        List<CtxNodeDimension> controllerDims = ctxNodeDimensionRepository.findByNodeId(controllerNode.getId());
        List<CtxNodeDimension> serviceDims = ctxNodeDimensionRepository.findByNodeId(serviceNode.getId());

        assertThat(controllerDims).isNotEmpty();
        assertThat(serviceDims).isNotEmpty();

        // Check if they share at least one dimension
        List<UUID> controllerDimIds =
                controllerDims.stream().map(nd -> nd.getDimension().getId()).toList();
        List<UUID> serviceDimIds =
                serviceDims.stream().map(nd -> nd.getDimension().getId()).toList();

        assertThat(controllerDimIds).anyMatch(serviceDimIds::contains);
    }

    @Test
    void failureCase_emptyAnnotatedTree_doesNotThrow() {
        // Clear all nodes to simulate empty tree
        ctxNodeRepository.deleteAll();

        // Execute - should not throw
        dimensionDiscoveryService.discoverDimensions(batch);

        // Verify no dimensions were created
        List<CtxDimension> dimensions = ctxDimensionRepository.findByBatchId(batch.getId());
        assertThat(dimensions).isEmpty();
    }

    @Test
    void failureCase_insufficientDimensionsAfterSecondShot_throwsException() {
        // Reset matchers to simulate LLM failing to find dimensions even after second shot
        fakeChatModel.reset();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("If you lack sufficient context")
                .response("""
                                {
                                  "domains": [],
                                  "archetypes": [],
                                  "crossCutting": [],
                                  "additionalContextRequest": {
                                    "ambiguousFilePaths": ["src/main/java/com/example/UserController.java"],
                                    "rationale": "Need more context",
                                    "certainDimensions": [],
                                    "expectedResolution": "Will help"
                                  }
                                }
                                """)
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("You previously requested additional context")
                .response("""
                                {
                                  "domains": [],
                                  "archetypes": [],
                                  "crossCutting": [],
                                  "additionalContextRequest": null
                                }
                                """)
                .build());

        // Execute and verify exception
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dimensionDiscoveryService.discoverDimensions(batch))
                .isInstanceOf(DimensionDiscoveryService.DimensionDiscoveryException.class)
                .hasMessageContaining("Insufficient dimensions discovered");
    }

    @Test
    void leadingDoubleAsteriskGlobs_matchRelativeNodePaths() {
        fakeChatModel.reset();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("If you lack sufficient context")
                .response("""
                                {
                                  "domains": [{"name": "User Management", "globPatterns": ["**/com/example/**/*.java"]}],
                                  "archetypes": [{"name": "Controller", "globPatterns": ["**/*Controller.java"]}],
                                  "crossCutting": [{"name": "Utils", "globPatterns": ["**/*util*.java"]}],
                                  "additionalContextRequest": null
                                }
                                """)
                .build());

        dimensionDiscoveryService.discoverDimensions(batch);

        List<CtxNodeDimension> controllerDims = ctxNodeDimensionRepository.findByNodeId(controllerNode.getId());
        assertThat(controllerDims).isNotEmpty();
    }

    @Test
    void zeroTaggedFiles_retriesDiscoveryOnceWithGuidance_thenSucceeds() {
        fakeChatModel.reset();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("If you lack sufficient context")
                .response("""
                                {
                                  "domains": [{"name": "User Management", "globPatterns": ["**/nomatch/**/*.java"]}],
                                  "archetypes": [{"name": "Controller", "globPatterns": ["**/nomatch/**/*.java"]}],
                                  "crossCutting": [{"name": "Utils", "globPatterns": ["**/nomatch/**/*.java"]}],
                                  "additionalContextRequest": null
                                }
                                """)
                .build());
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Your previous attempt produced dimension glob patterns")
                .response("""
                                {
                                  "domains": [{"name": "User Management", "globPatterns": ["*User*.java"]}],
                                  "archetypes": [{"name": "Controller", "globPatterns": ["*Controller.java"]}],
                                  "crossCutting": [{"name": "Utils", "globPatterns": ["*util*.java"]}],
                                  "additionalContextRequest": null
                                }
                                """)
                .build());

        dimensionDiscoveryService.discoverDimensions(batch);

        assertThat(ctxNodeDimensionRepository.findByNodeId(controllerNode.getId()))
                .isNotEmpty();
        assertThat(fakeChatModel.getInvocations()).hasSize(2);
    }

    @Test
    void zeroTaggedFiles_afterRetry_failsIngestion() {
        fakeChatModel.reset();
        String noMatchResponse = """
                        {
                          "domains": [{"name": "User Management", "globPatterns": ["**/nomatch/**/*.java"]}],
                          "archetypes": [{"name": "Controller", "globPatterns": ["**/nomatch/**/*.java"]}],
                          "crossCutting": [{"name": "Utils", "globPatterns": ["**/nomatch/**/*.java"]}],
                          "additionalContextRequest": null
                        }
                        """;
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("If you lack sufficient context")
                .response(noMatchResponse)
                .build());
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Your previous attempt produced dimension glob patterns")
                .response(noMatchResponse)
                .build());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dimensionDiscoveryService.discoverDimensions(batch))
                .isInstanceOf(DimensionDiscoveryService.DimensionDiscoveryException.class)
                .hasMessageContaining("matched no files");
    }
}
