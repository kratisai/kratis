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
                        "Supported Mermaid types: flowchart, graph, sequenceDiagram, classDiagram, stateDiagram-v2, erDiagram, C4Context/Container/Component, pie, quadrantChart, xychart-beta, journey.",
                        "Quote all display text. Give every subgraph an explicit ID and quoted title. One statement per line; close every block.",
                        "No front matter, init directives, styling, links, click statements, or HTML tags. Preserve useful detail; correct reported syntax rather than simplifying.",
                        "Use varied supported types of diagram (including journey, C4, stateDiagram-v2 etc) or chart (including pie, quadrantChart) that best fits the content.",
                        ""));
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
