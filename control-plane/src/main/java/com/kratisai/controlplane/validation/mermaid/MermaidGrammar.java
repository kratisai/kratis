package com.kratisai.controlplane.validation.mermaid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class MermaidGrammar {
    public static final String GUIDANCE = guidance();

    private static String guidance() {
        // Hack to avoid spotbugs complaint
        return String.join(
                " ",
                List.of(
                        "Write custom Kratis diagrams: a Mermaid-inspired subset, not full Mermaid. Supported types: flowchart, graph, sequenceDiagram, classDiagram, stateDiagram-v2, erDiagram, C4Context/Container/Component, pie, quadrantChart, xychart-beta, journey.",
                        "Always wrap every diagram in a fenced code block with language mermaid (never use a diagram type such as flowchart, sequenceDiagram, or C4Context as the fence language, and never omit the fence language); put the diagram header on the first line inside the block, e.g. flowchart LR.",
                        "Always quote display text and labels with double quotes; unquoted labels are not part of this format.",
                        "Keep each line simple and unambiguous; quote all node, edge, subgraph, participant, state, and label text.",
                        "Flowchart rules: quote all node text, e.g. A[\"Node Label\"]; quote all edge labels, e.g. A -->|\"Edge Label\"| B or A -- \"Edge Label\" --> B; subgraphs must have explicit IDs and quoted names, e.g. subgraph ID[\"Title\"]; keep subgraph and node IDs unique and do not use a subgraph ID as an edge endpoint inside its own subgraph; use direction TD/TB/BT/LR/RL.",
                        "Class diagram rules: class IDs may contain letters, digits, underscores, or dots; quote relation labels with spaces around the colon, e.g. A --> B : \"Label\"; inheritance and reverse arrows are allowed, e.g. A <|-- B or A --|> B; members may be inside braces or written as A : +method().",
                        "Sequence diagram rules: left-to-right arrows only (->>, -->>); declare each participant once; aliases use participant ID as \"Display Name\"; participant and message names may contain words separated by spaces; grouped messages use rect ... end.",
                        "State diagram rules: e.g. state \"State Label\" as S1, [*] --> S1; composite states use state S1 { ... } and close with }; direction TD/TB/BT/LR/RL is allowed; state descriptions use S1 : \"text\".",
                        "ER diagram rules: use ENTITY { attributes } and ENTITY ||--o{ ENTITY : \"label\"; do not use ER cardinality in a flowchart.",
                        "C4 rules: use Person, System, Container, Component, ContainerDb, SystemDb, ContainerQueue, SystemQueue, ComponentDb, ComponentQueue, boundaries and Rel; C4 accepts one title statement.",
                        "Pie and journey headers support optional inline titles, e.g. pie title \"Distribution\".",
                        "Journey tasks use task: score: role; the role is optional.",
                        "One statement per line; close every block.",
                        "No front matter, init directives, click/link/href directives, or HTML tags. Styling directives (style/classDef/linkStyle) are ignored. Do not use Mermaid keywords as IDs.",
                        "Preserve useful detail; correct reported syntax rather than simplifying.",
                        "Use varied supported types of diagram (including journey, C4, stateDiagram-v2 etc) or chart (including pie, quadrantChart) that best fits the content."));
    }

    public static MermaidGrammar load() {
        try (InputStream input = MermaidGrammar.class.getResourceAsStream("/mermaid-grammar/grammar.json")) {
            if (input == null) throw new IllegalStateException("Bundled Mermaid grammar is missing");
            return new MermaidGrammar(new ObjectMapper().readTree(input));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled Mermaid grammar", e);
        }
    }

    record FlowShape(String open, List<String> closes) {}

    final Map<String, Set<String>> reserved;
    final Map<String, Set<String>> headers;
    final List<FlowShape> flowShapes;
    final List<String> flowArrows;
    final Pattern identifier;
    final Pattern identifierPrefix;
    final Pattern flowIdentifier;
    final Pattern flowIdentifierPrefix;
    final Pattern number;
    final Pattern coordinate;
    final int maxInputUtf16Units;
    final int maxDepth;
    final int journeyScoreMin;
    final int journeyScoreMax;

    public MermaidGrammar(JsonNode grammar) {
        Objects.requireNonNull(grammar);
        reserved = readSets(grammar.required("reserved"));
        headers = readSets(grammar.required("headers"));
        JsonNode flowSyntax = grammar.required("flowchartSyntax");
        List<FlowShape> shapes = new ArrayList<>();
        flowSyntax
                .required("nodeShapes")
                .forEach(shape ->
                        shapes.add(new FlowShape(shape.required("open").asText(), readList(shape.required("closes")))));
        flowShapes = List.copyOf(shapes);
        flowArrows = readList(flowSyntax.required("arrows"));
        JsonNode patterns = grammar.required("patterns");
        String identifierPattern = patterns.required("identifier").asText();
        identifier = Pattern.compile(identifierPattern);
        identifierPrefix = Pattern.compile(identifierPattern.substring(0, identifierPattern.length() - 1));
        String flowIdentifierPattern = patterns.required("flowIdentifier").asText();
        flowIdentifier = Pattern.compile(flowIdentifierPattern);
        flowIdentifierPrefix = Pattern.compile(flowIdentifierPattern.substring(0, flowIdentifierPattern.length() - 1));
        number = Pattern.compile(patterns.required("number").asText());
        coordinate = Pattern.compile(patterns.required("coordinate").asText());
        JsonNode bounds = grammar.required("bounds");
        maxInputUtf16Units = bounds.required("maxInputUtf16Units").asInt();
        maxDepth = bounds.required("maxDepth").asInt();
        journeyScoreMin = bounds.required("journeyScoreMin").asInt();
        journeyScoreMax = bounds.required("journeyScoreMax").asInt();
    }

    public boolean isHeaderKeyword(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (Set<String> keywords : headers.values()) {
            if (keywords.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> readList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static Map<String, Set<String>> readSets(JsonNode node) {
        Map<String, Set<String>> result = new HashMap<>();
        node.properties().forEach(entry -> {
            Set<String> values = new HashSet<>();
            entry.getValue().forEach(value -> values.add(value.asText()));
            result.put(entry.getKey(), Set.copyOf(values));
        });
        return Map.copyOf(result);
    }
}
