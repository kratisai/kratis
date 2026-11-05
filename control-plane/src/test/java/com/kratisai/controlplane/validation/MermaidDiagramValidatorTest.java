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
import java.util.Arrays;
import java.util.List;
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
        List<String> issues = validator.findIssues(unquotedInline);
        assertThat(issues).singleElement().asString().contains("unquoted inline edge label", "-- \"label\" -->");
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
}
