package com.kratisai.controlplane.validation.mermaid;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import org.junit.jupiter.api.Test;

class MermaidNormalizerTest {

    private final MermaidNormalizer normalizer = new MermaidNormalizer();
    private final MermaidDiagramValidator validator = new MermaidDiagramValidator();

    private static String fence(String source) {
        return "```mermaid\n" + source + (source.endsWith("\n") ? "" : "\n") + "```\n";
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

        String normalized = normalizer.normalize(input);

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

        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    void normalizeFences_doesNotJudgeBlockquoteFenceByContentAfterQuoteExit() {
        String input = """
                > ```
                > just some quoted text

                flowchart LR
                """;

        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    void normalize_addsMissingC4Commas() {
        String input = fence("""
                C4Context
                Person(player "Player")
                """);

        String normalized = normalizer.normalize(input);

        assertThat(normalized).contains("Person(player, \"Player\")");
        assertThat(validator.findIssues(normalized)).isEmpty();
    }

    @Test
    void normalize_addsCommasAcrossC4PositionalArguments() {
        String input = fence("""
                C4Container
                Container(spa "Signlines SPA" "Vite + React 18" "Application shell and routing between scenes")
                """);

        String normalized = normalizer.normalize(input);

        assertThat(normalized)
                .contains(
                        "Container(spa, \"Signlines SPA\", \"Vite + React 18\", \"Application shell and routing between scenes\")");
        assertThat(validator.findIssues(normalized)).isEmpty();
    }

    @Test
    void normalize_addsCommaBeforeC4RelationLabel() {
        String input = fence("""
                C4Context
                Person(a, "A")
                System(b, "B")
                Rel(a, b "calls")
                """);

        String normalized = normalizer.normalize(input);

        assertThat(normalized).contains("Rel(a, b, \"calls\")");
        assertThat(validator.findIssues(normalized)).isEmpty();
    }

    @Test
    void normalize_doesNotGuessAmbiguousBareC4Arguments() {
        String input = fence("""
                C4Context
                Person(a, "A")
                System(b, "B")
                Rel(a b, "calls")
                """);

        assertThat(normalizer.normalize(input)).isEqualTo(input);
        assertThat(validator.findIssues(normalizer.normalize(input))).isNotEmpty();
    }

    @Test
    void normalize_leavesUnsupportedOrIncompleteC4ShapesUntouched() {
        assertThat(normalizer.normalize(fence("C4Context\nPerson(\"player\", \"Player\")")))
                .contains("Person(\"player\", \"Player\")");
        assertThat(normalizer.normalize(fence("C4Context\nPerson(player)"))).contains("Person(player)");
        assertThat(normalizer.normalize(fence("C4Context\nPerson(player \"Player\" bad)")))
                .contains("Person(player \"Player\" bad)");
    }

    @Test
    void normalize_diagnosticExplainsMissingC4CommasWhenRepairIsNotSafe() {
        String input = fence("""
                C4Context
                Person(player "Player" bad)
                """);

        String normalized = normalizer.normalize(input);

        assertThat(normalized).isEqualTo(input);
        assertThat(validator.findIssues(normalized))
                .anySatisfy(issue -> assertThat(issue).contains("C4 arguments must be comma-separated"));
    }

    @Test
    void normalize_doesNotTouchNonC4Diagrams() {
        String input = fence("""
                flowchart LR
                A["Start"] --> B["End"]
                """);

        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    void normalize_doesNotFixC4InsideAnotherCodeFence() {
        String input = """
                ```markdown
                ```mermaid
                C4Context
                Person(player "Player")
                ```
                ```
                """;

        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    void normalize_preservesBlockquotePrefixesAroundC4Fix() {
        String input = """
                > ```mermaid
                > C4Context
                > Person(player "Player")
                > ```
                """;

        assertThat(normalizer.normalize(input)).isEqualTo("""
                > ```mermaid
                > C4Context
                > Person(player, "Player")
                > ```
                """);
    }

    @Test
    void normalize_isIdempotent() {
        String input = fence("""
                C4Context
                Person(player "Player")
                """);

        String once = normalizer.normalize(input);

        assertThat(normalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    void normalize_rejectsFixWhenBlockStillFailsValidation() {
        String input = fence("""
                C4Context
                Person(player "Player")
                Rel(a b "calls")
                """);

        assertThat(normalizer.normalize(input)).isEqualTo(input);
        assertThat(validator.findIssues(normalizer.normalize(input))).isNotEmpty();
    }
}
