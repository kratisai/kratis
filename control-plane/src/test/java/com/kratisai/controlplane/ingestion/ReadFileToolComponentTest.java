package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
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
    void exposesOneReadFileToolWithSingleAndMultiplePathOptions() {
        var callbacks = ToolCallbacks.from(readFileTool);

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("read_file");
    }

    @Test
    void readFileTool_pathTraversal_returnsError() {
        var result = readFileTool.readFile("../../etc/passwd", null);
        assertThat(result.error()).contains("Path traversal");
    }

    @Test
    void logsOnlyTheRelativePathAndRangeWhenAnAssistedReadIsRequested(@TempDir Path cloneRoot) throws IOException {
        Path batchDir = cloneRoot.resolve(batch.getId().toString());
        Files.createDirectories(batchDir.resolve("src"));
        Files.writeString(batchDir.resolve("src/client.ts"), "one\ntwo\nthree");
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setCloneDir(cloneRoot.toString());
        ReadFileTool tool = new ReadFileTool(ingestionProperties);
        IngestionBatchLogService.BatchLogger batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        ToolContext context = new ToolContext(Map.of("batchId", batch.getId(), "batchLogger", batchLogger));

        var result = tool.readFileInput("src/client.ts", null, 2, 1, context);

        assertThat(result.content()).isEqualTo("2: two\n[lines 2-2 of 3; continue with offset=3]");
        verify(batchLogger).debug("ASSIST_SURFACES_READ_FILE", "path=src/client.ts offset=2 limit=1 lines=3");
    }

    @Test
    void readFilesReadsMultipleFilesWithIndependentRanges(@TempDir Path cloneRoot) throws IOException {
        Path batchDir = cloneRoot.resolve(batch.getId().toString());
        Files.createDirectories(batchDir.resolve("src"));
        Files.writeString(batchDir.resolve("src/one.ts"), "one\ntwo\nthree");
        Files.writeString(batchDir.resolve("src/two.ts"), "alpha\nbeta\ngamma");
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setCloneDir(cloneRoot.toString());
        ReadFileTool tool = new ReadFileTool(ingestionProperties);
        ToolContext context = new ToolContext(Map.of("batchId", batch.getId()));

        var result = tool.readFileInput(null, List.of("src/one.ts", "src/two.ts"), 2, 1, context)
                .files();

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().content()).contains("2: two");
        assertThat(result.get(1).content()).contains("2: beta");
        assertThat(result).extracting(ReadFileTool.FileRead::relativePath).containsExactly("src/one.ts", "src/two.ts");
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
        IngestionProperties ingestionProperties = new IngestionProperties();
        ingestionProperties.setCloneDir(tmpDir.toString());
        ReadFileTool tool = new ReadFileTool(ingestionProperties);
        ToolContext ctx = new ToolContext(java.util.Map.of("batchId", batch.getId()));

        var result = tool.readFile("test.java", ctx);
        assertThat(result.content()).isEqualTo("1: public class Test {}\n[lines 1-1 of 1]");

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
