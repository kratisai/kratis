package com.kratisai.controlplane.validation.mermaid;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MermaidDiagramRepairerTest {

    private final MermaidNormalizer normalizer = new MermaidNormalizer();
    private final MermaidDiagramRepairer repairer = new MermaidDiagramRepairer(normalizer);
    private final MermaidDiagramValidator validator = new MermaidDiagramValidator();

    @Test
    void repairDiagrams_replacesInvalidAndUnclosedMermaidBlocks() {
        String input = """
                Intro

                ```mermaid
                flowchart LR
                A -> B
                ```

                ```mermaid
                sequenceDiagram
                A->>B: hi
                """;

        String repaired = repairer.repairDiagrams(
                input,
                req -> switch (req.diagramNumber()) {
                    case 1 -> """
                            ```mermaid
                            flowchart LR
                            A["quoted"] --> B["end"]
                            ```""";
                    case 2 -> """
                            ```mermaid
                            sequenceDiagram
                            A->>B: hi
                            ```""";
                    default -> req.source();
                },
                Runnable::run);

        assertThat(validator.findIssues(repaired)).isEmpty();
        assertThat(repaired).contains("A[\"quoted\"] --> B[\"end\"]");
        assertThat(repaired.trim()).endsWith("```");
    }

    @Test
    void repairDiagrams_normalizesBeforeRequestingRepair() {
        String input = """
                ```mermaid
                C4Context
                Person(player "Player")
                ```
                """;

        List<Integer> requested = new ArrayList<>();

        String repaired = repairer.repairDiagrams(
                input,
                req -> {
                    requested.add(req.diagramNumber());
                    return null;
                },
                Runnable::run);

        assertThat(requested).isEmpty();
        assertThat(repaired).contains("Person(player, \"Player\")");
        assertThat(validator.findIssues(repaired)).isEmpty();
    }

    @Test
    void repairDiagrams_runsMultipleRepairsConcurrentlyOnProvidedExecutor() {
        String input = """
                ```mermaid
                flowchart LR
                A -> B
                ```

                ```mermaid
                flowchart LR
                C -> D
                ```
                """;
        var latch = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            String repaired = repairer.repairDiagrams(
                    input,
                    req -> {
                        latch.countDown();
                        try {
                            if (!latch.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Repairs did not execute concurrently");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return req.diagramNumber() == 1
                                ? "```mermaid\nflowchart LR\nA[\"1\"] --> B[\"2\"]\n```"
                                : "```mermaid\nflowchart LR\nC[\"3\"] --> D[\"4\"]\n```";
                    },
                    pool);

            assertThat(validator.findIssues(repaired)).isEmpty();
            assertThat(repaired).contains("A[\"1\"] --> B[\"2\"]", "C[\"3\"] --> D[\"4\"]");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blockquoteExitImplicitlyClosesFenceWithoutSwallowingTrailingContent() {
        String input = """
                > ```mermaid
                > flowchart LR
                > A -> B

                Trailing prose.

                ```mermaid
                flowchart LR
                C --> D
                ```
                """;

        assertThat(normalizer.normalize(input)).isEqualTo(input);

        String repaired = repairer.repairDiagrams(
                input,
                req -> {
                    assertThat(req.unclosed()).isFalse();
                    assertThat(req.source()).doesNotContain("Trailing prose.");
                    return """
                            ```mermaid
                            flowchart LR
                            A["1"] --> B["2"]
                            ```""";
                },
                Runnable::run);

        assertThat(validator.findIssues(repaired)).isEmpty();
        assertThat(repaired).contains("Trailing prose.");
        assertThat(repaired).contains("C --> D");
    }

    @Test
    void repairDiagrams_numbersDiagramsConsistentlyWithFindIssuesBeforeNormalization() {
        String input = """
                ```flowchart
                A --> B
                ```

                ```mermaid
                flowchart LR
                C -> D
                ```
                """;

        List<Integer> seenNumbers = new ArrayList<>();
        repairer.repairDiagrams(
                input,
                req -> {
                    seenNumbers.add(req.diagramNumber());
                    return null;
                },
                Runnable::run);

        assertThat(seenNumbers).containsExactly(2);
    }

    @Test
    void repairDiagrams_splicesCompletedRepairsWhenAnotherFailsInParallel() {
        String input = """
                ```mermaid
                flowchart LR
                A -> B
                ```

                ```mermaid
                flowchart LR
                C -> D
                ```
                """;
        var pool = Executors.newFixedThreadPool(2);
        try {
            String repaired = repairer.repairDiagrams(
                    input,
                    req -> req.diagramNumber() == 1 ? "```mermaid\nflowchart LR\nA[\"1\"] --> B[\"2\"]\n```" : null,
                    pool);

            assertThat(repaired).contains("A[\"1\"] --> B[\"2\"]");
            assertThat(repaired).contains("C -> D");
        } finally {
            pool.shutdownNow();
        }
    }
}
