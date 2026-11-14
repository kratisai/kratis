package com.kratisai.controlplane.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.ingestion.WriteWikiPageTool;
import com.kratisai.controlplane.planningagent.CanvasTool;
import com.kratisai.controlplane.validation.mermaid.MermaidGrammar;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognition;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MermaidDiagramValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MermaidDiagramValidator validator = new MermaidDiagramValidator();

    private static Path grammarDirectory() {
        Path relative = Path.of("../protocol/mermaid-grammar");
        if (Files.isDirectory(relative)) return relative;
        Path local = Path.of("protocol/mermaid-grammar");
        if (Files.isDirectory(local)) return local;
        throw new IllegalStateException("Cannot locate protocol/mermaid-grammar");
    }

    private static JsonNode cases() throws Exception {
        return MAPPER.readTree(grammarDirectory().resolve("cases.json").toFile());
    }

    private static String fence(String source) {
        return "```mermaid\n" + source + (source.endsWith("\n") ? "" : "\n") + "```\n";
    }

    @Test
    void toolDescriptionsDoNotInlineGuidance() {
        for (Class<?> tool : new Class<?>[] {CanvasTool.class, WriteWikiPageTool.class}) {
            assertThat(Arrays.stream(tool.getDeclaredMethods())
                            .map(method -> method.getAnnotation(KratisTool.class))
                            .filter(annotation -> annotation != null
                                    && (annotation.name().equals("write_canvas")
                                            || annotation.name().equals("patch_canvas")
                                            || annotation.name().equals("write_wiki_page"))))
                    .allSatisfy(
                            annotation -> assertThat(annotation.description()).doesNotContain(MermaidGrammar.GUIDANCE))
                    .hasSize(tool == CanvasTool.class ? 2 : 1);
        }
    }

    @Test
    void acceptsPlainMarkdownAndSupportedDiagrams() {
        assertThat(validator.findIssues(null)).isEmpty();
        assertThat(validator.findIssues("plain markdown")).isEmpty();
        assertThat(validator.findIssues("```mermaid\nflowchart LR\nA[\"Start\"] --> B\n```\n"))
                .isEmpty();
    }

    @Test
    void reportsEachInvalidDiagramAndOriginalBlockLine() {
        String markdown = """
                ```mermaid
                flowchart LR
                A --> B
                ```
                ~~~~mermaid extra
                sequenceDiagram

                A->>B: hello
                invalid statement
                ~~~~
                ```mermaid
                gantt
                task
                ```
                """;
        assertThat(validator.findIssues(markdown))
                .hasSize(2)
                .first()
                .asString()
                .contains("diagram #2", "line 4", "syntax.statement");
        assertThat(validator.findIssues(markdown).get(1)).contains("diagram #3", "line 1", "input.header");
    }

    @Test
    void ignoresFencesInsideOtherCodeBlocksAndReportsUnclosedMermaid() {
        assertThat(validator.findIssues("````text\n```mermaid\ngantt\n```\n````\n"))
                .isEmpty();
        assertThat(validator.findIssues("text\n```mermaid\nflowchart LR\nA --> B"))
                .singleElement()
                .asString()
                .contains("diagram #1", "unclosed fence", "Markdown line 2");
    }

    @Test
    void quoteScopeExitDoesNotHideFollowingDiagram() {
        String content = "> ```text\noutside\n```mermaid\ngantt\n```\n";
        assertThat(validator.findIssues(content)).singleElement().asString().contains("diagram #1", "input.header");
        assertThat(validator.findIssues("> ```mermaid\n> flowchart TD\n> A --> B\n\nnext"))
                .isEmpty();
        assertThat(validator.findIssues("- ```text\noutside\n```mermaid\ngantt\n```\n"))
                .singleElement()
                .asString()
                .contains("input.header");
    }

    @Test
    void ignoresIndentedCodeThatIsNotAFencedDiagram() {
        assertThat(validator.findIssues("    ```mermaid\n    gantt\n    ```\n")).isEmpty();
        assertThat(validator.findIssues("\t```mermaid\n\tgantt\n\t```\n")).isEmpty();
        assertThat(validator.findIssues(">     ```mermaid\n>     gantt\n>     ```\n"))
                .isEmpty();
        assertThat(validator.findIssues("- item\n\n      ```mermaid\n      gantt\n      ```\n"))
                .isEmpty();
        assertThat(validator.findIssues("-     ```mermaid\n      gantt\n      ```\n"))
                .isEmpty();
        assertThat(validator.findIssues("- item\n\noutside\n    ```mermaid\n    gantt\n    ```\n"))
                .isEmpty();
        assertThat(validator.findIssues("    ```mermaid\n    gantt\n    ```\n\n```mermaid\ngantt\n```\n"))
                .singleElement()
                .asString()
                .contains("diagram #1", "input.header");
    }

    @Test
    void nestedListFencesFailClosed() {
        assertThat(validator.findIssues("- ```mermaid\n  gantt\n  ```\n"))
                .singleElement()
                .asString()
                .contains("diagram #1", "nested fence", "top-level");
        assertThat(validator.findIssues("> - item\n>     ```mermaid\n>     gantt\n>     ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- item\n\n    ```mermaid\n    gantt\n    ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- item\nlazy continuation\n    ```mermaid\n    gantt\n    ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- one\n  - two\n    ```mermaid\n    gantt\n    ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- item\n    > ```mermaid\n    > gantt\n    > ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- - - ```mermaid\n      gantt\n      ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- > ```mermaid\n  > gantt\n  > ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("-    ```mermaid\n     gantt\n     ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("1. item\n    ```mermaid\n    gantt\n    ```\n"))
                .singleElement()
                .asString()
                .contains("nested fence");
        assertThat(validator.findIssues("- item\n  ```mermaid\n  gantt\n  ```\n"))
                .singleElement()
                .asString()
                .contains("input.header");
    }

    @Test
    void validatesIndentedAndLongFencesWithCrLf() {
        assertThat(validator.findIssues("   ~~~~mermaid\r\nflowchart LR\r\ninvalid syntax\r\n   ~~~~\r\n"))
                .singleElement()
                .asString()
                .contains("diagram #1", "line 2");
    }

    @Test
    void acceptsEverySharedAcceptedCaseThroughMarkdown() throws Exception {
        for (JsonNode test : cases()) {
            if (!test.required("ok").asBoolean()) continue;
            JsonNode source = test.get("source");
            if (source == null || !source.isTextual() || source.asText().isBlank()) continue;
            assertThat(validator.findIssues(fence(source.asText())))
                    .as(test.required("name").asText())
                    .isEmpty();
        }
    }

    @Test
    void rejectsEverySharedRejectedCaseThroughMarkdown() throws Exception {
        for (JsonNode test : cases()) {
            if (test.required("ok").asBoolean()) continue;
            JsonNode source = test.get("source");
            if (source == null || !source.isTextual() || source.asText().isBlank()) continue;
            String rule = test.required("rule").asText();
            if (rule.equals("input.control") || rule.equals("input.type")) continue;
            String name = test.required("name").asText();
            assertThat(validator.findIssues(fence(source.asText())))
                    .as(name)
                    .singleElement()
                    .asString()
                    .contains("diagram #1", "(" + rule + ")");
        }
    }

    @Test
    void pieTitleInlineValidatesWithoutError() {
        String markdown = fence("""
                pie title "Code Dimensions"
                "A" : 10
                "B" : 20
                """);
        assertThat(validator.findIssues(markdown)).isEmpty();
    }

    @Test
    void flowchartAmpersandGroupingParsesCorrectly() {
        String source1 = fence("""
                flowchart LR
                A & B --> C
                """);
        assertThat(validator.findIssues(source1)).isEmpty();

        String source2 = fence("""
                flowchart LR
                A --> B & C
                """);
        assertThat(validator.findIssues(source2)).isEmpty();
    }

    @Test
    void flowchartInlineEdgeLabelValidatesAndRendersCleanly() {
        String source = """
                flowchart LR
                A["Node A"] -- "label" --> B["Node B"]
                """;
        assertThat(validator.findIssues(fence(source))).isEmpty();

        MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());
        MermaidRecognition result = recognizer.recognize(source);
        assertThat(result.ok()).isTrue();
        assertThat(result.renderText()).isEqualTo("flowchart LR\nA[\"Node A\"] -->|\"label\"| B[\"Node B\"]");
    }

    @Test
    void unquotedLabelsTriggerActionableValidationErrors() {
        String unquotedInline = fence("""
                flowchart LR
                A -- label --> B
                """);
        List<String> unquotedInlineIssues = validator.findIssues(unquotedInline);
        assertThat(unquotedInlineIssues)
                .singleElement()
                .asString()
                .contains("unquoted inline edge label", "-- \"label\" -->");
    }

    @Test
    void unsafeLabelsTriggerActionableValidationErrors() {
        String unquoted = fence("""
                flowchart LR
                A[bad | label] --> B
                """);
        List<String> issues = validator.findIssues(unquoted);
        assertThat(issues).singleElement().asString().contains("unsafe or ambiguous label", "A[\"API (v1)\"]");
    }

    @Test
    void stateDiagramHeaderWorksIdenticallyToStateDiagramV2() {
        String stateV1 = """
                stateDiagram
                [*] --> Active
                Active --> [*]
                """;
        String stateV2 = """
                stateDiagram-v2
                [*] --> Active
                Active --> [*]
                """;
        assertThat(validator.findIssues(fence(stateV1))).isEmpty();
        assertThat(validator.findIssues(fence(stateV2))).isEmpty();

        MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());
        MermaidRecognition res1 = recognizer.recognize(stateV1);
        MermaidRecognition res2 = recognizer.recognize(stateV2);
        assertThat(res1.ok()).isTrue();
        assertThat(res2.ok()).isTrue();
        assertThat(res1.type()).isEqualTo("stateDiagram-v2");
        assertThat(res2.type()).isEqualTo("stateDiagram-v2");
        assertThat(res1.renderText()).isEqualTo(res2.renderText());
    }

    @Test
    void normalizeFences_rewritesHeaderFenceAndUntaggedDiagramFence() {
        String input = """
                ```flowchart TB
                A["Client"] --> B["API"]
                ```

                ```
                sequenceDiagram
                A->>B: hello
                ```
                """;

        String normalized = validator.normalizeFences(input);

        assertThat(normalized).isEqualTo("""
                ```mermaid
                flowchart TB
                A["Client"] --> B["API"]
                ```

                ```mermaid
                sequenceDiagram
                A->>B: hello
                ```
                """);
        assertThat(validator.findIssues(normalized)).isEmpty();
    }

    @Test
    void normalizeFences_leavesNonMermaidCodeFencesUntouched() {
        String input = """
                ```java
                class Foo {}
                ```

                ```
                plain text note
                ```
                """;

        assertThat(validator.normalizeFences(input)).isEqualTo(input);
    }

    @Test
    void findIssues_rejectsNonMermaidDiagramFencesAndReportsInnerSyntaxErrors() {
        String input = """
                ```C4Context
                Person(User, "User", "Platform user")
                User --> controlplane
                ```
                """;

        List<String> issues = validator.findIssues(input);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("must use 'mermaid' as the fence language");
        assertThat(issues.get(1)).contains("unsupported C4 element");
    }

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

        String repaired = validator.repairDiagrams(
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
            String repaired = validator.repairDiagrams(
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

        assertThat(validator.normalizeFences(input)).isEqualTo(input);

        String repaired = validator.repairDiagrams(
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
    void normalizeFences_doesNotJudgeBlockquoteFenceByContentAfterQuoteExit() {
        String input = """
                > ```
                > just some quoted text

                flowchart LR
                """;

        assertThat(validator.normalizeFences(input)).isEqualTo(input);
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
        validator.repairDiagrams(
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
            String repaired = validator.repairDiagrams(
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
