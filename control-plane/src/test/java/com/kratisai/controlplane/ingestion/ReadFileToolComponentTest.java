package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class ReadFileToolComponentTest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private ReadFileTool readFileTool;

    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext context = testDataFactory.createDefaultContext("ReadFile Team", "test-repo");
        batch = context.batch();
        batch.setActive(true);
        ingestionBatchRepository.saveAndFlush(batch);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void readFileTool_pathTraversal_returnsError() {
        var result = readFileTool.readFile("../../etc/passwd", null);
        assertThat(result.error()).contains("Path traversal");
    }

    @Test
    void readFileTool_validFile_returnsContent() throws IOException {
        // Create a temp file simulating a cloned repo file
        Path tmpDir = Files.createTempDirectory("kratis-ingest");
        Path batchDir = tmpDir.resolve(batch.getId().toString());
        Files.createDirectories(batchDir);
        Path testFile = batchDir.resolve("test.java");
        Files.writeString(testFile, "public class Test {}");

        // Use a ReadFileTool with the temp base dir
        ReadFileTool tool = new ReadFileTool(tmpDir.toString());
        ToolContext ctx = new ToolContext(java.util.Map.of("batchId", batch.getId()));

        var result = tool.readFile("test.java", ctx);
        assertThat(result.content()).isEqualTo("public class Test {}");

        // Cleanup
        Files.deleteIfExists(testFile);
        Files.deleteIfExists(batchDir);
        Files.deleteIfExists(tmpDir);
    }

    @Test
    void readFileTool_fileNotFound_returnsError() {
        ToolContext ctx = new ToolContext(java.util.Map.of("batchId", batch.getId()));
        var result = readFileTool.readFile("nonexistent/file.java", ctx);
        assertThat(result.error()).contains("File not found");
    }
}
