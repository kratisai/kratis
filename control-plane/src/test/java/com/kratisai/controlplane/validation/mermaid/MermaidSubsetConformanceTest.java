package com.kratisai.controlplane.validation.mermaid;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MermaidSubsetConformanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Set<String> PRIMITIVES = Set.of(
            "newline",
            "optional-space",
            "double-quote",
            "nonempty-text-without-double-quote",
            "unwrapped-text",
            "nonzero-digit",
            "letter",
            "digit",
            "digits",
            "integer",
            "flow-id",
            "shape-open",
            "shape-close",
            "title-with-spaces",
            "task-text",
            "annotation",
            "bare-member",
            "c4raw");
    private static final Set<String> PROSE_WORDS =
            Set.of("a", "token", "from", "of", "arrows", "flowchartSyntax", "yntax", "flowchart");
    private static final Set<String> STRUCTURAL_WORDS = Set.of("direction", "role");

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
    void guidanceMatchesCustomSubsetPolicy() {
        assertThat(MermaidGrammar.GUIDANCE)
                .doesNotContain("```")
                .contains(
                        "custom Kratis diagrams",
                        "Mermaid-inspired subset",
                        "Always quote display text and labels",
                        "stateDiagram-v2",
                        "quadrantChart")
                .contains("unquoted labels are not part of this format");
    }

    @Test
    void productionReferencesAreResolved() throws Exception {
        JsonNode grammar = read("grammar.json");
        JsonNode productions = grammar.required("productions");
        List<String> violations = new ArrayList<>();
        for (String scope : List.of(
                "flowchart",
                "sequenceDiagram",
                "classDiagram",
                "stateDiagram-v2",
                "erDiagram",
                "C4Context",
                "C4Container",
                "C4Component",
                "c4",
                "pie",
                "quadrantChart",
                "xychart-beta",
                "journey")) {
            Map<String, String> rules = productionRules(productions.required(scope));
            Set<String> defined = new HashSet<>(
                    productionRules(productions.required("common")).keySet());
            if (scope.startsWith("C4") || scope.equals("c4")) {
                defined.addAll(productionRules(productions.required("c4")).keySet());
            }
            defined.addAll(rules.keySet());
            rules.forEach((name, rhs) -> {
                for (String token : nonTerminalReferences(rhs)) {
                    if (!defined.contains(token)) {
                        violations.add(scope + "." + name + " references undefined " + token);
                    }
                }
            });
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void productionsHaveConformanceCoverage() throws Exception {
        Set<String> productionKeys = new TreeSet<>();
        JsonNode productions = read("grammar.json").required("productions");
        productions
                .fieldNames()
                .forEachRemaining(scope -> productions.required(scope).forEach(rule -> {
                    String name = rule.asText()
                            .substring(0, rule.asText().indexOf(" ="))
                            .trim();
                    productionKeys.add(scope + "." + name);
                }));
        List<String> missing = new ArrayList<>();
        for (String key : productionKeys) {
            String caseName = productionCoverage().get(key);
            if (caseName == null) {
                missing.add(key);
                continue;
            }
            boolean found = false;
            for (JsonNode test : read("cases.json")) {
                if (test.required("name").asText().equals(caseName)) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.add(key + " -> " + caseName);
        }
        assertThat(missing).as("productions without conformance coverage").isEmpty();
    }

    private static Map<String, String> productionRules(JsonNode rulesNode) {
        Map<String, String> rules = new LinkedHashMap<>();
        rulesNode.forEach(rule -> {
            String value = rule.asText();
            rules.put(value.substring(0, value.indexOf(" =")).trim(), value);
        });
        return rules;
    }

    private static List<String> nonTerminalReferences(String rhs) {
        String withoutQuotedTerminals = rhs.replaceAll("'[^']*'", " ");
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(withoutQuotedTerminals);
        while (matcher.find()) {
            String token = matcher.group();
            if (PRIMITIVES.contains(token) || STRUCTURAL_WORDS.contains(token)) continue;
            if (PROSE_WORDS.contains(token)) continue;
            tokens.add(token);
        }
        return tokens;
    }

    private static Map<String, String> productionCoverage() {
        return Map.<String, String>ofEntries(
                Map.entry("common.diagram", "flowchart common source forms"),
                Map.entry("common.id", "flow numeric and case-sensitive identifiers"),
                Map.entry("common.number", "pie punctuation"),
                Map.entry("common.coordinate", "quadrant coordinates"),
                Map.entry("common.quoted", "flowchart classic shapes"),
                Map.entry("common.bare", "flow unquoted HTML"),
                Map.entry("flowchart.header", "flowchart common source forms"),
                Map.entry("flowchart.direction", "flowchart lowercase direction"),
                Map.entry("flowchart.node", "flowchart classic shapes"),
                Map.entry("flowchart.node-group", "flow ampersand source node grouping"),
                Map.entry("flowchart.arrow", "flowchart dotted arrow tokens"),
                Map.entry("flowchart.inline-edge", "flow inline edge label solid"),
                Map.entry("flowchart.inline-solid-edge", "flow inline edge label solid"),
                Map.entry("flowchart.inline-solid-close", "flow inline edge label solid"),
                Map.entry("flowchart.inline-dotted-edge", "flow inline edge label dotted"),
                Map.entry("flowchart.inline-dotted-close", "flow inline edge label dotted"),
                Map.entry("flowchart.inline-thick-edge", "flow inline edge label thick"),
                Map.entry("flowchart.inline-thick-close", "flow inline edge label thick"),
                Map.entry("flowchart.edge-label", "flowchart common source forms"),
                Map.entry("flowchart.unquoted-label", "flowchart classic shapes"),
                Map.entry("flowchart.subgraph-title", "flowchart common source forms"),
                Map.entry("flowchart.statement", "flowchart common source forms"),
                Map.entry("sequenceDiagram.arrow", "sequence all supported arrows"),
                Map.entry("sequenceDiagram.sequence-name", "sequence authoring wrappers"),
                Map.entry("sequenceDiagram.statement", "sequence blocks"),
                Map.entry("classDiagram.arrow", "class reverse relations"),
                Map.entry("classDiagram.statement", "class members and relationship"),
                Map.entry("classDiagram.class-id", "class dotted name"),
                Map.entry("stateDiagram-v2.state-id", "state numeric identifier"),
                Map.entry("stateDiagram-v2.number-start-id", "state numeric identifier"),
                Map.entry("stateDiagram-v2.state-ref", "state standalone declaration"),
                Map.entry("stateDiagram-v2.statement", "state composite accepted"),
                Map.entry("stateDiagram-v2.state-display", "state spaced identifier"),
                Map.entry("stateDiagram-v2.state-display-bare", "state spaced identifier"),
                Map.entry("erDiagram.name", "ER bare relation and parameterized types"),
                Map.entry("erDiagram.cardinality", "ER bare relation and parameterized types"),
                Map.entry("erDiagram.key", "er composite keys with whitespace"),
                Map.entry("erDiagram.attribute-type", "ER attributes"),
                Map.entry("erDiagram.statement", "ER bare relation and parameterized types"),
                Map.entry("C4Context.statement", "C4 context"),
                Map.entry("C4Container.statement", "C4 container boundary"),
                Map.entry("C4Component.statement", "C4 component boundary"),
                Map.entry("c4.element", "C4 context"),
                Map.entry("c4.boundary", "C4 container boundary"),
                Map.entry("c4.relation", "C4 forward relation"),
                Map.entry("c4.c4-statement", "C4 context"),
                Map.entry("pie.header", "pie inline header title"),
                Map.entry("pie.statement", "pie punctuation"),
                Map.entry("quadrantChart.statement", "quadrant coordinates"),
                Map.entry("xychart-beta.statement", "xy categories"),
                Map.entry("journey.header", "journey inline header title"),
                Map.entry("journey.statement", "journey sections"));
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
