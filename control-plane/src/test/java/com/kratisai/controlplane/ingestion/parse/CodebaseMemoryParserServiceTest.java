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
import com.kratisai.controlplane.model.IngestionBatch;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        parserService = new CodebaseMemoryParserService(
                ctxNodeRepository,
                ctxEdgeRepository,
                processExecutor,
                new ObjectMapper(),
                BINARY_PATH,
                cacheDir.toString());

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
        Path staleDatabase = cacheDir.resolve(deriveProjectName(cloneDir) + ".db");
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
                        "{\"repo_path\":\"" + cloneDir.toAbsolutePath() + "\"}");
        assertThat(envCaptor.getValue()).containsEntry("CBM_CACHE_DIR", cacheDir.toString());
    }

    private String deriveProjectName(Path directory) {
        String absolutePath = directory.toAbsolutePath().toString();
        return absolutePath.replaceAll("^/+", "").replace("/", "-").replace("\\", "-");
    }
}
