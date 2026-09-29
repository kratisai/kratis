package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

abstract @SpringIntegrationTest class BaseCodebaseMemoryParserIntegrationTest {

    @Autowired
    protected CtxNodeRepository ctxNodeRepository;

    @Autowired
    protected CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    protected IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    protected RepositoryRepository repositoryRepository;

    @Autowired
    protected ProcessExecutor processExecutor;

    @Autowired
    protected TestDataFactory testDataFactory;

    @Autowired
    protected DatabaseCleaner databaseCleaner;

    @Value("${kratis.parser.binary-path}")
    protected String configuredBinaryPath;

    protected IngestionBatch batch;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        batch = ctx.batch();
    }

    /**
     * Resolve the codebase-memory binary path for test execution. Returns null if binary is not found or
     * not executable.
     */
    protected File resolveBinaryPath() {
        File parentRelative = new File("../" + configuredBinaryPath);
        File directPath = new File(configuredBinaryPath);

        if (parentRelative.exists() && parentRelative.canExecute()) {
            return parentRelative;
        } else if (directPath.exists() && directPath.canExecute()) {
            return directPath;
        }
        return null;
    }

    @Autowired
    protected ObjectMapper objectMapper;

    /** Create the parser service with the resolved binary path. */
    protected CodebaseMemoryParserService createParserService(File binaryFile) {
        return new CodebaseMemoryParserService(
                ctxNodeRepository,
                ctxEdgeRepository,
                new ProcessExecutor(),
                objectMapper,
                binaryFile.getAbsolutePath(),
                System.getProperty("java.io.tmpdir"));
    }

    /** Run the parser on the given sample directory. */
    protected void runParser(CodebaseMemoryParserService parserService, Path sampleDir) {
        parserService.prepareAst(batch, sampleDir);
    }

    /**
     * Assert that nodes of a given type exist with the expected size, scoped to the current batch
     * to prevent cross-test contamination when sibling tests share the same DB schema.
     */
    protected void assertNodesOfType(NodeType nodeType, int expectedSize) {
        UUID batchId = batch.getId();
        List<CtxNode> nodes = ctxNodeRepository.findAll();
        List<CtxNode> matching = nodes.stream()
                .filter(n -> batchId.equals(n.getBatch().getId()) && nodeType.equals(n.getNodeType()))
                .toList();
        assertThat(matching).as("Missing %s node", nodeType).hasSize(expectedSize);
    }

    /** Assert that edges of a given relation type exist with the expected size, scoped to the current batch. */
    protected void assertEdgeOfType(RelationType relationType, int expectedSize) {
        UUID batchId = batch.getId();
        List<CtxEdge> edges = ctxEdgeRepository.findAll();
        List<CtxEdge> matching = edges.stream()
                .filter(e -> batchId.equals(e.getBatch().getId()) && relationType.equals(e.getRelationType()))
                .toList();
        assertThat(matching).as("Missing %s edge", relationType).hasSize(expectedSize);
    }
}
