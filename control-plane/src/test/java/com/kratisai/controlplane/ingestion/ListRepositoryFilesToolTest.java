package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

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

class ListRepositoryFilesToolTest {

    @TempDir
    Path root;

    @Test
    void inventoryListsEveryFileWithoutNameRulesAndSkipsDependencyDirectories() throws IOException {
        Files.createDirectories(root.resolve("ops"));
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.createDirectories(root.resolve(".github/workflows"));
        Files.writeString(root.resolve("ops/app-settings.yaml"), "x");
        Files.writeString(root.resolve("release.sh"), "x");
        Files.writeString(root.resolve(".github/workflows/ship.yml"), "x");
        Files.writeString(root.resolve("node_modules/pkg/package.json"), "x");

        assertThat(ListRepositoryFilesTool.inventory(root))
                .containsExactly(".github/workflows/ship.yml", "ops/app-settings.yaml", "release.sh");
    }

    @Test
    void inventoryOfMissingRootIsEmpty() {
        assertThat(ListRepositoryFilesTool.inventory(root.resolve("absent"))).isEmpty();
    }

    @Test
    void listFilesOmitsExcludedPathsFromToolContext() throws IOException {
        UUID batchId = UUID.randomUUID();
        Path cloneDir = root.resolve(batchId.toString());
        Files.createDirectories(cloneDir.resolve("src"));
        Files.createDirectories(cloneDir.resolve("cypress/support"));
        Files.writeString(cloneDir.resolve("src/Canvas.tsx"), "x");
        Files.writeString(cloneDir.resolve("cypress/support/MockWebsocket.ts"), "x");

        IngestionProperties properties = new IngestionProperties();
        properties.setCloneDir(root.toString());
        ListRepositoryFilesTool tool = new ListRepositoryFilesTool(properties);

        var result = tool.listFiles(
                "**/*.ts*",
                new ToolContext(
                        Map.of("batchId", batchId, "excludedPaths", Set.of("cypress/support/MockWebsocket.ts"))));
        assertThat(result.paths()).containsExactly("src/Canvas.tsx");
    }
}
