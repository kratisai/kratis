package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionDiscoveryServiceTest {

    @TempDir
    Path tempDir;

    private DimensionDiscoveryService service;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        service =
                new DimensionDiscoveryService(null, null, null, null, null, null, null, null, null, tempDir.toString());

        Team team = new Team();
        team.setId(UUID.randomUUID());
        Repository repo = new Repository();
        repo.setTeam(team);
        batch = new IngestionBatch(repo);
        batch.setId(UUID.randomUUID());
    }

    @Test
    void matchesGlob_exactMatch_shouldReturnTrue() {
        String filePath = "src/main/java/com/example/UserController.java";
        String pattern = "**/*Controller.java";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_fallback1_shouldReturnTrue() {
        String filePath = "src/main/java/com/example/UserController.java";
        String pattern = "src/main/java/com/example/**/*Controller.java";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_fallback2_shouldReturnTrue() {
        String filePath = "src/main/java/com/example/UserController.java";
        String pattern = "src/main/java/com/example/**/*";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_filenameOnly_shouldReturnTrue() {
        String filePath = "src/main/java/com/example/UserController.java";
        String pattern = "*Controller.java";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_noMatch_shouldReturnFalse() {
        String filePath = "src/main/java/com/example/UserService.java";
        String pattern = "**/*Controller.java";
        assertThat(service.matchesGlob(filePath, pattern)).isFalse();
    }

    @Test
    void matchesGlob_invalidPattern_shouldReturnFalse() {
        String filePath = "src/main/java/com/example/UserController.java";
        String pattern = "[unclosed bracket";
        assertThat(service.matchesGlob(filePath, pattern)).isFalse();
    }

    @Test
    void matchesGlob_leadingDoubleAsterisk_matchesRelativePath() {
        String filePath = "src/game/components/CracklingFireSoundEffect.tsx";
        String pattern = "**/src/game/components/**/*.tsx";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_leadingDoubleAsterisk_matchesDeepNestedPath() {
        String filePath = "src/game/scenes/levels/Level1.tsx";
        String pattern = "**/src/game/scenes/**/*.tsx";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_leadingDoubleAsterisk_matchesTopLevelDir() {
        String filePath = "cypress/e2e/game.cy.ts";
        String pattern = "**/cypress/e2e/**/*.cy.ts";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_leadingDoubleAsterisk_matchesStateSlice() {
        String filePath = "src/game/state/gameSlice.ts";
        String pattern = "**/src/game/state/*Slice.ts";
        assertThat(service.matchesGlob(filePath, pattern)).isTrue();
    }

    @Test
    void matchesGlob_leadingDoubleAsterisk_matchesTestFiles() {
        assertThat(service.matchesGlob("tests/unit/game.test.ts", "**/tests/**/*.test.*"))
                .isTrue();
        assertThat(service.matchesGlob("tests/common/game.test.tsx", "**/tests/**/*.test.*"))
                .isTrue();
    }

    @Test
    void matchesGlob_leadingDoubleAsterisk_doesNotMatchWrongDirectory() {
        String filePath = "src/game/pixijs/GameBoard.tsx";
        String pattern = "**/src/game/components/**/*.tsx";
        assertThat(service.matchesGlob(filePath, pattern)).isFalse();
    }

    @Test
    void globPatternVariants_stripsLeadingDoubleAsteriskAndCollapses() {
        List<String> variants = DimensionDiscoveryService.globPatternVariants("**/src/game/components/**/*.tsx");
        assertThat(variants)
                .contains("**/src/game/components/**/*.tsx")
                .contains("src/game/components/**/*.tsx")
                .contains("**/src/game/components/*.tsx")
                .contains("src/game/components/*.tsx");
    }

    @Test
    void graphPropagation_maxHopsConstraint_shouldNotExceedMaxHops() {
        // Simulate graph propagation logic
        int maxHops = 2;
        Map<String, List<String>> adjacencyList = new HashMap<>();
        adjacencyList.put("A", List.of("B"));
        adjacencyList.put("B", List.of("A", "C"));
        adjacencyList.put("C", List.of("B", "D"));
        adjacencyList.put("D", List.of("C", "E"));
        adjacencyList.put("E", List.of("D"));

        Set<String> seedNodes = Set.of("A");
        Set<String> visited = new HashSet<>(seedNodes);
        Queue<String> queue = new LinkedList<>(seedNodes);
        Map<String, Integer> distances = new HashMap<>();
        for (String seed : seedNodes) {
            distances.put(seed, 0);
        }

        Set<String> propagatedNodes = new HashSet<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDist = distances.get(current);

            if (currentDist >= maxHops) {
                continue;
            }

            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    distances.put(neighbor, currentDist + 1);
                    queue.add(neighbor);
                    propagatedNodes.add(neighbor);
                }
            }
        }

        // A is seed (dist 0)
        // B is dist 1
        // C is dist 2
        // D and E should NOT be propagated because maxHops is 2
        assertThat(propagatedNodes).containsExactlyInAnyOrder("B", "C");
        assertThat(propagatedNodes).doesNotContain("D", "E");
    }

    @Test
    void graphPropagation_multipleSeeds_shouldPropagateCorrectly() {
        int maxHops = 2;
        Map<String, List<String>> adjacencyList = new HashMap<>();
        adjacencyList.put("A", List.of("B", "C"));
        adjacencyList.put("B", List.of("A", "D"));
        adjacencyList.put("C", List.of("A", "E"));
        adjacencyList.put("D", List.of("B"));
        adjacencyList.put("E", List.of("C"));

        Set<String> seedNodes = Set.of("A", "D");
        Set<String> visited = new HashSet<>(seedNodes);
        Queue<String> queue = new LinkedList<>(seedNodes);
        Map<String, Integer> distances = new HashMap<>();
        for (String seed : seedNodes) {
            distances.put(seed, 0);
        }

        Set<String> propagatedNodes = new HashSet<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDist = distances.get(current);

            if (currentDist >= maxHops) {
                continue;
            }

            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    distances.put(neighbor, currentDist + 1);
                    queue.add(neighbor);
                    propagatedNodes.add(neighbor);
                }
            }
        }

        // A and D are seeds (dist 0)
        // B and C are dist 1 from A
        // E is dist 2 from A (via C)
        assertThat(propagatedNodes).containsExactlyInAnyOrder("B", "C", "E");
    }

    @Test
    void readAmbiguousFiles_existingFile_shouldReturnContent() throws Exception {
        // Arrange
        Path cloneDir = tempDir.resolve(batch.getId().toString());
        Files.createDirectories(cloneDir);
        Path testFile = cloneDir.resolve("test.java");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            lines.add("line " + i);
        }
        Files.write(testFile, lines);

        // Act
        String result = service.readAmbiguousFiles(batch, List.of("test.java"));

        // Assert
        assertThat(result).contains("=== File: test.java ===");
        assertThat(result).contains("line 1");
        assertThat(result).contains("line 100");
        assertThat(result).doesNotContain("line 101");
    }

    @Test
    void readAmbiguousFiles_fileNotFound_shouldReturnNotFoundMarker() {
        // Act
        String result = service.readAmbiguousFiles(batch, List.of("missing.java"));

        // Assert
        assertThat(result).contains("=== File: missing.java ===");
        assertThat(result).contains("[FILE NOT FOUND]");
    }

    @Test
    void readAmbiguousFiles_pathTraversalAttempt_shouldReturnAccessDenied() throws Exception {
        // Arrange
        Path cloneDir = tempDir.resolve(batch.getId().toString());
        Files.createDirectories(cloneDir);
        Path outsideFile = tempDir.resolve("outside.java");
        Files.writeString(outsideFile, "secret");

        // Act
        String result = service.readAmbiguousFiles(batch, List.of("../outside.java"));

        // Assert
        assertThat(result).contains("=== File: ../outside.java ===");
        assertThat(result).contains("[ACCESS DENIED]");
    }
}
