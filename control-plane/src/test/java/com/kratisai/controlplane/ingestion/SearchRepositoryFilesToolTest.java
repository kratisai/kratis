package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.config.IngestionProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

class SearchRepositoryFilesToolTest {

    @TempDir
    Path root;

    @Test
    void searchesMatchingTextFilesSkipsExcludedAndNulBinaryFilesAndGuardsAgainstBacktracking() throws IOException {
        UUID batchId = UUID.randomUUID();
        Path cloneDir = root.resolve(batchId.toString());
        Files.createDirectories(cloneDir.resolve("src/components"));
        Files.createDirectories(cloneDir.resolve("cypress/support"));
        Files.writeString(
                cloneDir.resolve("src/components/DealTilesButton.tsx"),
                "import { useGameServerConnection } from '@glark-newco/game-library';\n"
                        + "const conn = useGameServerConnection(fn);\n"
                        + "a".repeat(60)
                        + "!\n");
        Files.writeString(
                cloneDir.resolve("cypress/support/MockWebsocket.ts"),
                "export const useGameServerConnection = () => {};\n");
        // ASCII + NUL bytes: valid UTF-8, must still be skipped as binary even without excludedPaths!
        Files.writeString(cloneDir.resolve("src/components/ascii-nul.bin"), "useGameServerConnection\0binary\n");
        Files.write(cloneDir.resolve("src/components/sprite.png"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00});

        IngestionProperties properties = new IngestionProperties();
        properties.setCloneDir(root.toString());
        SearchRepositoryFilesTool tool = new SearchRepositoryFilesTool(properties);
        ToolContext context = new ToolContext(
                Map.of("batchId", batchId, "excludedPaths", Set.of("cypress/support/MockWebsocket.ts")));

        var hits = tool.searchFiles("useGameServerConnection", "src/**/*", context);
        assertThat(hits.error()).isNull();
        assertThat(hits.truncated()).isFalse();
        assertThat(hits.matches()).hasSize(2);
        assertThat(hits.matches())
                .allSatisfy(hit -> assertThat(hit.file()).isEqualTo("src/components/DealTilesButton.tsx"));

        // Catastrophic-backtracking regex "(a+)+$" against "a".repeat(60) + "!" must abort via step bound immediately.
        long startNs = System.nanoTime();
        var redosResult = tool.searchFiles("(a+)+$", "src/**/*.tsx", context);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertThat(redosResult.matches()).isEmpty();
        assertThat(elapsedMs).isLessThan(1000L);

        // Unbalanced '(' falls back to literal quote instead of failing.
        var literalFallback = tool.searchFiles("useGameServerConnection(", null, context);
        assertThat(literalFallback.matches()).hasSize(1);
        assertThat(literalFallback.matches().getFirst().line()).isEqualTo(2);

        assertThat(tool.searchFiles(" ", null, context).error()).contains("blank");
        assertThat(tool.searchFiles("foo", "../secret/*", context).error()).contains("traversal");
        assertThatThrownBy(() -> tool.searchFiles("foo", null, new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void capsPerFileAndTotalMatchesWhenPatternIsWidespread() throws IOException {
        UUID batchId = UUID.randomUUID();
        Path cloneDir = root.resolve(batchId.toString());
        Files.createDirectories(cloneDir.resolve("src"));
        String tenLines = ("match_token " + "x".repeat(200) + "\n").repeat(10);
        for (int i = 0; i < 10; i++) {
            Files.writeString(cloneDir.resolve("src/File" + i + ".ts"), tenLines);
        }

        IngestionProperties properties = new IngestionProperties();
        properties.setCloneDir(root.toString());
        SearchRepositoryFilesTool tool = new SearchRepositoryFilesTool(properties);
        var result = tool.searchFiles("match_token", null, new ToolContext(Map.of("batchId", batchId)));

        assertThat(result.matches()).hasSize(SearchRepositoryFilesTool.MAX_RESULTS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.matches().getFirst().snippet()).endsWith("...");
    }

    @Test
    void aggregateScanBudgetStopsOversizedMultiFileScans() throws IOException {
        UUID batchId = UUID.randomUUID();
        Path cloneDir = root.resolve(batchId.toString());
        Files.createDirectories(cloneDir.resolve("src"));
        String longLine = "a".repeat(SearchRepositoryFilesTool.MAX_LINE_CHARS) + "\n";
        String content = longLine.repeat(280);
        for (int i = 0; i < 2; i++) {
            Files.writeString(cloneDir.resolve("src/Large" + i + ".ts"), content);
        }

        IngestionProperties properties = new IngestionProperties();
        properties.setCloneDir(root.toString());
        SearchRepositoryFilesTool tool = new SearchRepositoryFilesTool(properties);

        long startNs = System.nanoTime();
        var result = tool.searchFiles("never_matches", null, new ToolContext(Map.of("batchId", batchId)));
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertThat(result.matches()).isEmpty();
        assertThat(result.truncated()).isTrue();
        assertThat(elapsedMs).isLessThan(1000L);
    }
}
