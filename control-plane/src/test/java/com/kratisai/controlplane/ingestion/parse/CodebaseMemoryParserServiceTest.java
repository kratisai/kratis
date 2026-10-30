package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.config.ParserProperties;
import com.kratisai.controlplane.model.CtxEdge;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class CodebaseMemoryParserServiceTest {

    private static final String BINARY_PATH = "/usr/local/bin/codebase-memory-mcp";

    @TempDir
    Path cacheDir;

    @TempDir
    Path cloneDir;

    private CtxNodeRepository ctxNodeRepository;
    private CtxEdgeRepository ctxEdgeRepository;
    private ProcessExecutor processExecutor;
    private CodebaseMemoryParserService parserService;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        ctxNodeRepository = mock(CtxNodeRepository.class);
        ctxEdgeRepository = mock(CtxEdgeRepository.class);
        processExecutor = mock(ProcessExecutor.class);
        ParserProperties parserProperties = new ParserProperties();
        parserProperties.setBinaryPath(BINARY_PATH);
        parserProperties.setTempDir(cacheDir.toString());
        parserService = new CodebaseMemoryParserService(
                ctxNodeRepository, ctxEdgeRepository, processExecutor, new ObjectMapper(), parserProperties);

        Team team = new Team("team", "description");
        team.setId(UUID.randomUUID());
        Repository repository = new Repository("test-repo", "file:///dev/null", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        batch = new IngestionBatch(repository);
        batch.setId(UUID.randomUUID());
    }

    @Test
    void failsWhenBinaryExitsNonZero() throws Exception {
        String loaderError = "Error relocating /usr/local/bin/codebase-memory-mcp: __printf_chk: symbol not found";
        when(processExecutor.execute(anyList(), any(File.class), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(127, loaderError.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> parserService.prepareAst(batch, cloneDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("codebase-memory exited with code 127")
                .hasMessageContaining("__printf_chk");

        verifyNoInteractions(ctxNodeRepository);
        verifyNoInteractions(ctxEdgeRepository);
    }

    @Test
    void reportsExitCodeOnlyWhenNoOutputCaptured() throws Exception {
        when(processExecutor.execute(anyList(), any(File.class), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        assertThatThrownBy(() -> parserService.prepareAst(batch, cloneDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("codebase-memory exited with code 1");
    }

    @Test
    void truncatesLargeCapturedOutput() throws Exception {
        byte[] largeOutput = "x".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        when(processExecutor.execute(anyList(), any(File.class), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(1, largeOutput));

        Throwable thrown = catchThrowable(() -> parserService.prepareAst(batch, cloneDir));

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown.getMessage()).contains("... (output truncated)");
        assertThat(thrown.getMessage()).doesNotContain("x".repeat(5_000));
    }

    @Test
    void failsWhenSqliteDatabaseMissingDespiteExitZero() throws Exception {
        when(processExecutor.execute(anyList(), any(File.class), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));

        assertThatThrownBy(() -> parserService.prepareAst(batch, cloneDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQLite database not found at: ");
    }

    @Test
    void deletesStaleDatabaseAndInvokesBinaryWithExpectedCommand() throws Exception {
        Path staleDatabase = cacheDir.resolve(projectName() + ".db");
        Files.write(staleDatabase, new byte[] {1, 2, 3});

        when(processExecutor.execute(anyList(), any(File.class), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(127, new byte[0]));

        assertThatThrownBy(() -> parserService.prepareAst(batch, cloneDir)).isInstanceOf(RuntimeException.class);
        assertThat(staleDatabase).doesNotExist();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(processExecutor).execute(commandCaptor.capture(), eq(cloneDir.toFile()), envCaptor.capture());

        assertThat(commandCaptor.getValue())
                .containsExactly(
                        BINARY_PATH,
                        "cli",
                        "index_repository",
                        "--repo-path",
                        cloneDir.toAbsolutePath().toString(),
                        "--name",
                        projectName());
        assertThat(envCaptor.getValue()).containsEntry("CBM_CACHE_DIR", cacheDir.toString());
        assertThat(envCaptor.getValue()).containsEntry("CBM_WORKERS", "2");
        assertThat(envCaptor.getValue()).containsEntry("CBM_MEM_BUDGET_MB", "256");
    }

    @Test
    void resolvesSystemTempDirectoryToDedicatedCacheSubdirectory() {
        assertThat(CodebaseMemoryParserService.cacheDirFor("/tmp")).isEqualTo(Path.of("/tmp", "kratis-cbm-cache"));
        assertThat(CodebaseMemoryParserService.cacheDirFor("/var/tmp"))
                .isEqualTo(Path.of("/var/tmp", "kratis-cbm-cache"));
        assertThat(CodebaseMemoryParserService.cacheDirFor("/data/cache")).isEqualTo(Path.of("/data/cache"));
    }

    @Test
    void serializesConcurrentExecutionsViaSemaphore() throws Exception {
        CountDownLatch inFirstExecution = new CountDownLatch(1);
        CountDownLatch releaseFirstExecution = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger activeConcurrent = new AtomicInteger(0);

        when(processExecutor.execute(anyList(), any(File.class), any())).thenAnswer(invocation -> {
            int current = activeConcurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            inFirstExecution.countDown();
            if (!releaseFirstExecution.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("first execution was never released");
            }
            activeConcurrent.decrementAndGet();
            return new ProcessExecutor.ProcessResult(1, new byte[0]);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> f1 = executor.submit(() -> {
            try {
                parserService.prepareAst(batch, cloneDir);
            } catch (Exception ignored) {
            }
        });

        assertThat(inFirstExecution.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> f2 = executor.submit(() -> {
            try {
                parserService.prepareAst(batch, cloneDir);
            } catch (Exception ignored) {
            }
        });

        Thread.sleep(100);
        assertThat(activeConcurrent.get()).isEqualTo(1);
        assertThat(maxConcurrent.get()).isEqualTo(1);

        releaseFirstExecution.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void etlImportsOnlyNamedProjectMapsEdgesAndSkipsBuiltins() throws Exception {
        Path sqlitePath = cacheDir.resolve(projectName() + ".db");

        when(processExecutor.execute(anyList(), any(File.class), any())).thenAnswer(invocation -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath());
                    Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE nodes ("
                        + "id INTEGER PRIMARY KEY, "
                        + "project TEXT, "
                        + "label TEXT, "
                        + "name TEXT, "
                        + "qualified_name TEXT, "
                        + "file_path TEXT, "
                        + "start_line INTEGER, "
                        + "end_line INTEGER, "
                        + "properties TEXT)");
                stmt.execute("CREATE TABLE edges ("
                        + "id INTEGER PRIMARY KEY, "
                        + "project TEXT, "
                        + "source_id INTEGER, "
                        + "target_id INTEGER, "
                        + "type TEXT, "
                        + "properties TEXT)");

                String p = projectName();
                String missed = p + "::missed";
                try (PreparedStatement nodeInsert = conn.prepareStatement(
                                "INSERT INTO nodes (id, project, label, name, qualified_name, file_path, start_line, end_line, properties) VALUES (?, ?, ?, ?, ?, ?, 1, 10, ?)");
                        PreparedStatement edgeInsert = conn.prepareStatement(
                                "INSERT INTO edges (id, project, source_id, target_id, type, properties) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insertNode(nodeInsert, 1, p, "Struct", "UserServer", "main.UserServer", "server.go");
                    insertNode(nodeInsert, 2, p, "Function", "print", "print", "<python-builtins>");
                    insertNode(nodeInsert, 3, p, "Function", "handle", "main.handle", "server.go");
                    insertNode(nodeInsert, 4, missed, "File", "server.go", "server.go", "server.go");
                    insertEdge(edgeInsert, 101, p, 3, 1, "SPAWNS");
                    insertEdge(edgeInsert, 102, p, 1, 3, "MENTIONS");
                    insertEdge(edgeInsert, 103, p, 3, 1, "HAS_BRANCH");
                    insertEdge(edgeInsert, 104, p, 3, 1, "CALL_REFERENCE");
                    insertEdge(edgeInsert, 105, p, 1, 3, "REFERENCES_FILE");
                    insertEdge(edgeInsert, 106, p, 3, 1, "USAGE");
                    insertEdge(edgeInsert, 107, p, 3, 1, "NEW_UNKNOWN_KIND");
                    insertEdge(edgeInsert, 108, missed, 3, 1, "IMPORTS");
                }
            }
            return new ProcessExecutor.ProcessResult(0, new byte[0]);
        });

        parserService.prepareAst(batch, cloneDir);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<CtxNode>> nodeCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(ctxNodeRepository).saveAll(nodeCaptor.capture());
        Collection<CtxNode> savedNodes = nodeCaptor.getValue();
        assertThat(savedNodes).extracting(CtxNode::getSymbolName).containsExactlyInAnyOrder("UserServer", "handle");
        CtxNode structNode = savedNodes.stream()
                .filter(n -> "UserServer".equals(n.getSymbolName()))
                .findFirst()
                .orElseThrow();
        assertThat(structNode.getNodeType()).isEqualTo(NodeType.CLASS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CtxEdge>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(ctxEdgeRepository).saveAll(edgeCaptor.capture());
        assertThat(edgeCaptor.getValue())
                .extracting(CtxEdge::getRelationType)
                .containsExactlyInAnyOrder(
                        RelationType.CALLS, RelationType.CALLS, RelationType.IMPORTS, RelationType.IMPORTS);
    }

    private static void insertNode(
            PreparedStatement ps,
            int id,
            String project,
            String label,
            String name,
            String qualifiedName,
            String filePath)
            throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, project);
        ps.setString(3, label);
        ps.setString(4, name);
        ps.setString(5, qualifiedName);
        ps.setString(6, filePath);
        ps.setString(7, "{}");
        ps.executeUpdate();
    }

    private static void insertEdge(
            PreparedStatement ps, int id, String project, int sourceId, int targetId, String type) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, project);
        ps.setInt(3, sourceId);
        ps.setInt(4, targetId);
        ps.setString(5, type);
        ps.setString(6, "{}");
        ps.executeUpdate();
    }

    private String projectName() {
        return "kratis-" + batch.getId();
    }
}
