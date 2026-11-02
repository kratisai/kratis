package com.kratisai.controlplane.validation.mermaid;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MermaidSubsetConformanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path grammarDirectory() {
        Path relative = Path.of("../protocol/mermaid-grammar");
        if (Files.isDirectory(relative)) return relative;
        Path local = Path.of("protocol/mermaid-grammar");
        if (Files.isDirectory(local)) return local;
        throw new IllegalStateException("Cannot locate protocol/mermaid-grammar");
    }

    private static JsonNode read(String name) throws Exception {
        return MAPPER.readTree(grammarDirectory().resolve(name).toFile());
    }

    @Test
    void packagedGrammarMatchesCanonicalContract() throws Exception {
        try (var resource = MermaidSubsetConformanceTest.class.getResourceAsStream("/mermaid-grammar/grammar.json")) {
            assertThat(resource).isNotNull();
            assertThat(MAPPER.readTree(resource)).isEqualTo(read("grammar.json"));
        }
    }

    @Test
    void guidanceIsRulesOnly() {
        assertThat(MermaidGrammar.GUIDANCE).doesNotContain("```").contains("stateDiagram-v2", "quadrantChart");
    }

    @Test
    void acceptsReportedStandardFlowchartSource() {
        MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());
        for (String source : new String[] {
            "flowchart LR\nDB[(PostgreSQL)]",
            "flowchart LR\nREP --> DB[(PostgreSQL)]",
            "flowchart LR\nsubgraph External\nA\nend",
            "flowchart LR\nsubgraph API[API Layer]\nA\nend"
        }) {
            MermaidRecognition result = recognizer.recognize(source);
            assertThat(result.ok()).as("%s: %s", source, result.error()).isTrue();
            assertThat(result.source()).isEqualTo(source);
        }
    }

    @Test
    void canonicalizesProvenFlowchartForms() {
        MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());
        String[][] cases = {
            {"DB[(PostgreSQL)]", "DB[(\"PostgreSQL\")]"},
            {"REP --> DB[(PostgreSQL)]", "REP --> DB[(\"PostgreSQL\")]"},
            {"subgraph External\nA\nend", "subgraph External[\"External\"]\nA\nend"},
            {"subgraph API[API Layer]\nA\nend", "subgraph API[\"API Layer\"]\nA\nend"},
            {"subgraph External Services\nA\nend", "subgraph \"External Services\"\nA\nend"},
            {"subgraph \"External Services\"\nA\nend", "subgraph \"External Services\"\nA\nend"},
            {"A[API Layer]", "A[\"API Layer\"]"},
            {"A(API Layer)", "A(\"API Layer\")"},
            {"A([Start])", "A([\"Start\"])"},
            {"A[[Routine]]", "A[[\"Routine\"]]"},
            {"A((Circle))", "A((\"Circle\"))"},
            {"A(((Circle)))", "A(((\"Circle\")))"},
            {"A{Decision}", "A{\"Decision\"}"},
            {"A{{Prepare}}", "A{{\"Prepare\"}}"},
            {"A>Asymmetric]", "A>\"Asymmetric\"]"},
            {"A[/Data/]", "A[/\"Data\"/]"},
            {"A[\\Data\\]", "A[\\\"Data\"\\]"},
            {"A[/Shape\\]", "A[/\"Shape\"\\]"},
            {"A[\\Shape/]", "A[\\\"Shape\"/]"},
            {"A-->|Read records|B", "A -->|\"Read records\"| B"},
            {"A-->B-->C", "A --> B --> C"},
            {"A---oB", "A ---o B"},
            {"A---xB", "A ---x B"},
            {"A===oB", "A ===o B"},
            {"A===xB", "A ===x B"},
            {"A-.-oB", "A -.-o B"},
            {"A-.-xB", "A -.-x B"}
        };
        for (String[] fixture : cases) {
            String source = "flowchart LR\n" + fixture[0];
            String expected = "flowchart LR\n" + fixture[1];
            MermaidRecognition result = recognizer.recognize(source);
            assertThat(result.ok()).as("%s: %s", source, result.error()).isTrue();
            assertThat(result.source()).isEqualTo(source);
            assertThat(result.renderText()).isEqualTo(expected);
            assertThat(recognizer.recognize(result.renderText()).renderText()).isEqualTo(expected);
        }
    }

    @Test
    void syntaxErrorsSuggestConcreteSupportedExamples() {
        MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());
        assertThat(recognizer.recognize("flowchart LR\nDB[(Unclosed]").error())
                .contains("line 2", "DB[(\"Database\")]");
        assertThat(recognizer.recognize("flowchart LR\nA -z- B").error()).contains("line 2", "A --> B");
        assertThat(recognizer.recognize("pie\nSlice : 1").error())
                .contains("line 2", "expected a quoted label, e.g. \"Label\"");
    }

    @Test
    void corpusAndRulesConform() throws Exception {
        MermaidRecognizer recognizer = new MermaidRecognizer(new MermaidGrammar(read("grammar.json")));
        Set<String> exercised = new HashSet<>();
        for (JsonNode test : read("cases.json")) {
            String name = test.required("name").asText();
            JsonNode source = test.required("source");
            MermaidRecognition result = recognizer.recognize(source.isTextual() ? source.asText() : null);
            boolean ok = test.required("ok").asBoolean();
            assertThat(result.ok()).as(name).isEqualTo(ok);
            if (ok) {
                assertThat(result.renderText())
                        .as(name)
                        .isEqualTo(test.required("renderText").asText());
                assertThat(result.type())
                        .as(name)
                        .isEqualTo(test.required("type").asText());
                assertThat(result.source()).as(name).isEqualTo(source.asText());
                assertThat(result.rule()).as(name).isNull();
                MermaidRecognition again = recognizer.recognize(result.renderText());
                assertThat(again.ok()).as(name).isTrue();
                assertThat(again.renderText()).as(name).isEqualTo(result.renderText());
            } else {
                assertThat(result.rule())
                        .as(name)
                        .isEqualTo(test.required("rule").asText());
                exercised.add(result.rule());
            }
        }
        Set<String> rules = new HashSet<>();
        read("rules.json").forEach(rule -> rules.add(rule.required("id").asText()));
        assertThat(exercised).containsExactlyInAnyOrderElementsOf(rules);
    }

    @Test
    void acceptsEverySupportedType() throws Exception {
        MermaidRecognizer recognizer = new MermaidRecognizer(new MermaidGrammar(read("grammar.json")));
        Set<String> accepted = new TreeSet<>();
        for (JsonNode test : read("cases.json"))
            if (test.required("ok").asBoolean())
                accepted.add(
                        recognizer.recognize(test.required("source").asText()).type());
        Set<String> headers = new TreeSet<>();
        read("grammar.json").required("headers").fieldNames().forEachRemaining(headers::add);
        assertThat(accepted).containsExactlyInAnyOrderElementsOf(headers);
    }
}
