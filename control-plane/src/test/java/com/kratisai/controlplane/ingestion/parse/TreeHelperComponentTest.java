package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class TreeHelperComponentTest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private TreeHelper helper;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    private Team team;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("Annotated Tree Team", "test-repo");
        team = context.team();
        batch = context.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);

        CtxNode controllerNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserController.java");
        CtxNode repositoryNode = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.FILE, "src/main/java/com/example/UserRepository.java");
        CtxNode unknownNode =
                new CtxNode(batch, team.getId(), "test-repo", NodeType.FILE, "src/main/resources/application.yml");

        ctxNodeRepository.saveAll(List.of(controllerNode, repositoryNode, unknownNode));
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void buildAnnotatedTree_withNoSymbolChildren_emitsRawPaths() {
        String tree = helper.buildAnnotatedTree(batch);

        assertThat(tree).contains("src/main/java/com/example/UserController.java");
        assertThat(tree).contains("src/main/java/com/example/UserRepository.java");
        assertThat(tree).contains("src/main/resources/application.yml");
        // No parentheses since no symbol children
        assertThat(tree).doesNotContain("(");
    }

    @Test
    void buildAnnotatedTree_withSymbolChildAndInheritanceEdge_emitsFootprint() {
        // Add a symbol child for the controller file
        CtxNode classSymbol = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.CLASS, "src/main/java/com/example/UserController.java");
        classSymbol.setSymbolName("UserController");
        ctxNodeRepository.save(classSymbol);

        // Add a base class symbol
        CtxNode baseSymbol = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.CLASS, "src/main/java/com/example/BaseController.java");
        baseSymbol.setSymbolName("BaseController");
        ctxNodeRepository.save(baseSymbol);

        // Add INHERITS edge from classSymbol to baseSymbol
        CtxEdge extendsEdge = new CtxEdge(batch, team.getId(), classSymbol, baseSymbol, RelationType.INHERITS);
        ctxEdgeRepository.save(extendsEdge);

        String tree = helper.buildAnnotatedTree(batch);

        assertThat(tree)
                .contains("src/main/java/com/example/UserController.java (UserController : inherits BaseController)");
    }

    @Test
    void buildAnnotatedTree_withMetadataDecorators_emitsAnnotations() {
        CtxNode classSymbol = new CtxNode(
                batch, team.getId(), "test-repo", NodeType.CLASS, "src/main/java/com/example/UserController.java");
        classSymbol.setSymbolName("UserController");
        classSymbol.setMetadata("{\"decorators\": [\"RestController\", \"RequestMapping\"]}");
        ctxNodeRepository.save(classSymbol);

        String tree = helper.buildAnnotatedTree(batch);

        assertThat(tree).contains("@RestController");
        assertThat(tree).contains("@RequestMapping");
    }
}
