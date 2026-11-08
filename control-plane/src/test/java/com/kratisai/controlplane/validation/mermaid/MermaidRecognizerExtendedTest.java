package com.kratisai.controlplane.validation.mermaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MermaidRecognizerExtendedTest {
    private final MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());

    @Test
    void acceptsExtendedStateAndSequenceForms() {
        for (String source : new String[] {
            "stateDiagram-v2\nstate \"Ready\"",
            "stateDiagram-v2\nstate Request Received",
            "stateDiagram-v2\nstate \"Ready\"\nnote right of \"Ready\" : text",
            "stateDiagram-v2\nstate Request Received\nnote right of Request Received : text",
            "stateDiagram-v2\nstate A {\nstate B {\n}\n}",
            "stateDiagram-v2\nstate A {\n}",
            "sequenceDiagram\nNote over \"All Players\",B: text",
        }) {
            MermaidRecognition result = recognizer.recognize(source);
            assertThat(result.ok()).as(source + ": " + result.error()).isTrue();
        }
    }

    @Test
    void rejectsExtendedErrorBranches() {
        String[][] cases = {
            {"flowchart LR\n%%{init: {}}%%\nA --> B", "syntax.statement"},
            {"flowchart LR\ndirection ZZ\nA --> B", "syntax.statement"},
            {"flowchart LR\nA -- \"unterminated --> B", "syntax.statement"},
            {"flowchart LR\nA -- \"label\" -- B", "syntax.statement"},
            {"flowchart LR\nA -->", "syntax.statement"},
            {"flowchart LR\nA[", "syntax.statement"},
            {"flowchart LR\nA[\"x\"", "syntax.statement"},
            {"flowchart LR\nsubgraph A\nA --> B\nend", "flow.subgraph-id-unique"},
            {"flowchart LR\nsubgraph A\nB\nend\nsubgraph A\nC\nend", "flow.subgraph-id-unique"},
            {"flowchart LR\nsubgraph A\nA\nend", "flow.subgraph-id-unique"},
            {"classDiagram\nclass style", "identifier.reserved"},
            {"classDiagram\nA ??> B", "syntax.statement"},
            {"classDiagram\nA {", "syntax.statement"},
            {"stateDiagram-v2\nstate \"Foo\" as default", "identifier.reserved"},
            {"stateDiagram-v2\nstate A :", "syntax.statement"},
            {"stateDiagram-v2\nstate A {", "syntax.statement"},
            {"stateDiagram-v2\nstate A\nnote right of A", "syntax.statement"},
            {"stateDiagram-v2\nnote right of A", "state.note-after-state"},
            {"stateDiagram-v2\nstate A\nnote right of default : x", "identifier.reserved"},
            {"stateDiagram-v2\n}", "syntax.statement"},
            {"sequenceDiagram\nparticipant loop as Foo", "identifier.reserved"},
            {"sequenceDiagram\nA/B->>C: x", "syntax.statement"},
            {"sequenceDiagram\nrect x; y\nend", "text.statement-separator"},
            {"sequenceDiagram\nelse x", "sequence.branch"},
            {"sequenceDiagram\nfoo", "syntax.statement"},
            {"sequenceDiagram\nactivate Ghost", "sequence.activation-participant"},
            {"sequenceDiagram\nrect rgb(1,2,3)\nA->>B: x", "syntax.statement"},
            {"C4Context\nBogus(a, \"b\")", "syntax.statement"},
            {"C4Context\nPerson(a)", "syntax.statement"},
            {"C4Context\nRel(a, b, \"x\")", "c4.endpoint-declared"},
            {"C4Context\nBoundary(a, \"A\") {", "syntax.statement"},
            {"C4Context\nSystem(a, \"A\") {", "syntax.statement"},
            {"journey\nsection A\nTask: 9: User", "journey.score"},
            {"journey\nsection A\nBogus", "syntax.statement"},
            {"erDiagram\nA ||--o{ B : x; y", "text.statement-separator"},
        };
        for (String[] test : cases) {
            MermaidRecognition result = recognizer.recognize(test[0]);
            assertThat(result.ok()).as(test[0]).isFalse();
            assertThat(result.rule()).as(test[0]).isEqualTo(test[1]);
        }
    }

    @Test
    void longUnstructuredLinesFailWithoutBacktracking() {
        int spaces = 99_950;
        String[] sources = {
            "classDiagram\nA" + " ".repeat(spaces) + "x",
            "journey\nsection A\nTask" + " ".repeat(spaces) + "x",
            "sequenceDiagram\nA" + " ".repeat(spaces) + ": x",
            "sequenceDiagram\nNote over " + " ".repeat(spaces) + "x",
            "flowchart LR\nA -- " + " ".repeat(spaces) + "x",
            "stateDiagram-v2\nA" + " ".repeat(spaces) + "x",
        };
        for (String source : sources) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                MermaidRecognition result = recognizer.recognize(source);
                assertThat(result.ok()).as(source.substring(0, 30)).isFalse();
            });
        }
    }
}
