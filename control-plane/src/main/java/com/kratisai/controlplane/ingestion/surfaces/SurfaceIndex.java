package com.kratisai.controlplane.ingestion.surfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.NodeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class SurfaceIndex {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int DEFAULT_MAX_CHARS = 80_000;
    static final int INLINE_LEAF_MAX_FILES = 15;
    private static final int MAX_FACTS_PER_FILE = 6;

    private SurfaceIndex() {}

    static String build(List<CtxNode> nodes, List<String> inventory, Set<String> testFiles, int maxChars) {
        Map<String, List<CtxNode>> nodesByPath = new HashMap<>();
        for (CtxNode node : nodes) {
            if (node.getPath() != null && !node.getPath().isBlank()) {
                nodesByPath
                        .computeIfAbsent(node.getPath(), unused -> new ArrayList<>())
                        .add(node);
            }
        }
        Set<String> testPaths = new HashSet<>(testFiles);
        nodes.stream()
                .filter(node ->
                        node.getPath() != null && metadata(node).path("is_test").asBoolean(false))
                .map(CtxNode::getPath)
                .forEach(testPaths::add);

        List<String> paths = inventory.isEmpty()
                ? nodesByPath.keySet().stream().sorted().toList()
                : inventory.stream().distinct().sorted().toList();
        Map<String, List<String>> byDirectory = new TreeMap<>();
        Map<String, Integer> repoLanguages = new HashMap<>();
        int totalNonTestFiles = 0;
        for (String path : paths) {
            if (testPaths.contains(path) || GraphSurfaceTranslator.isTestPath(path)) {
                continue;
            }
            byDirectory
                    .computeIfAbsent(directory(path), unused -> new ArrayList<>())
                    .add(path);
            String lang = language(path, nodesByPath.getOrDefault(path, List.of()));
            repoLanguages.merge(lang, 1, Integer::sum);
            totalNonTestFiles++;
        }

        StringBuilder output = new StringBuilder();
        if (totalNonTestFiles > 0) {
            String langSummary = repoLanguages.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(item -> item.getKey() + "=" + item.getValue())
                    .collect(Collectors.joining(" "));
            String summaryLine = "Repository summary: "
                    + totalNonTestFiles
                    + " files across "
                    + byDirectory.size()
                    + " directories | languages: "
                    + langSummary
                    + "\n";
            if (summaryLine.length() <= maxChars) {
                output.append(summaryLine);
            }
        }

        int omitted = 0;
        boolean capped = false;
        for (Map.Entry<String, List<String>> entry : byDirectory.entrySet()) {
            String directory = entry.getKey();
            List<String> files = entry.getValue().stream().sorted().toList();
            boolean inlineAllLeaves = files.size() <= INLINE_LEAF_MAX_FILES;
            Map<String, Integer> languages = new LinkedHashMap<>();
            int resources = 0;
            int routes = 0;
            int channels = 0;
            int images = 0;
            int envKeys = 0;
            List<String> lines = new ArrayList<>();
            int collapsedLeaves = 0;
            for (String path : files) {
                List<CtxNode> fileNodes = nodesByPath.getOrDefault(path, List.of());
                String language = language(path, fileNodes);
                languages.merge(language, 1, Integer::sum);
                List<String> allFacts = facts(fileNodes);
                boolean hasBoundarySignal = false;
                for (String fact : allFacts) {
                    if (fact.startsWith("resource=")) {
                        resources++;
                        hasBoundarySignal = true;
                    } else if (fact.startsWith("route=")) {
                        routes++;
                        hasBoundarySignal = true;
                    } else if (fact.startsWith("channel=")) {
                        channels++;
                        hasBoundarySignal = true;
                    } else if (fact.startsWith("image=")) {
                        images++;
                        hasBoundarySignal = true;
                    } else if (fact.startsWith("env=")) {
                        envKeys++;
                        hasBoundarySignal = true;
                    } else if (fact.startsWith("symbol=Class:resource.") || fact.contains(" extends ")) {
                        hasBoundarySignal = true;
                    }
                }
                if (inlineAllLeaves || hasBoundarySignal) {
                    List<String> boundedFacts = capFacts(allFacts);
                    String line = boundedFacts.isEmpty()
                            ? "  " + path + " [" + language + "]"
                            : "  " + path + " [" + language + "] " + String.join(", ", boundedFacts);
                    lines.add(line);
                } else {
                    collapsedLeaves++;
                }
            }
            if (collapsedLeaves > 0) {
                String dirDisplay = directory.equals(".") ? "./" : directory + "/";
                lines.add("  (+"
                        + collapsedLeaves
                        + " other files in "
                        + dirDisplay
                        + "; use list_files or search_files to inspect)");
            }
            String header = (directory.equals(".") ? "./" : directory + "/") + "  ";
            header += files.size() + " files | "
                    + languages.entrySet().stream()
                            .map(item -> item.getKey() + "=" + item.getValue())
                            .collect(Collectors.joining(" "))
                    + " | resources=" + resources
                    + " routes=" + routes
                    + " channels=" + channels
                    + " images=" + images
                    + " env=" + envKeys
                    + "\n";
            if (output.length() + header.length() > maxChars) {
                capped = true;
                omitted += files.size();
                continue;
            }
            output.append(header);
            for (String line : lines) {
                if (output.length() + line.length() + 1 > maxChars) {
                    capped = true;
                    omitted++;
                    continue;
                }
                output.append(line).append('\n');
            }
        }
        if (capped) {
            output.append("(").append(omitted).append(" surface index entries omitted; use list_files to explore)\n");
        }
        return output.toString();
    }

    private static List<String> capFacts(List<String> facts) {
        if (facts.size() <= MAX_FACTS_PER_FILE) {
            return facts;
        }
        List<String> capped = new ArrayList<>(facts.subList(0, MAX_FACTS_PER_FILE));
        capped.add("(+" + (facts.size() - MAX_FACTS_PER_FILE) + " more)");
        return List.copyOf(capped);
    }

    private static List<String> facts(List<CtxNode> nodes) {
        List<String> facts = new ArrayList<>();
        for (CtxNode node : nodes) {
            String name = node.getSymbolName();
            if (name == null || name.isBlank()) {
                continue;
            }
            JsonNode metadata = metadata(node);
            switch (node.getNodeType()) {
                case ROUTE -> facts.add("route=" + name);
                case CHANNEL -> {
                    String transport = metadata.path("transport").asText("");
                    facts.add("channel=" + name + (transport.isBlank() ? "" : " (" + transport + ")"));
                }
                case RESOURCE -> facts.add("resource=" + name);
                case ARTIFACT -> facts.add("image=" + name);
                case VARIABLE -> {
                    if ("EnvVar".equals(metadata.path("cmmLabel").asText())) {
                        facts.add("env=" + name);
                    }
                }
                case CLASS -> facts.add(typeFact("Class", name, metadata));
                case INTERFACE -> {
                    String entry = entryFact(node, metadata);
                    if (entry != null) {
                        facts.add(entry);
                    } else if (hasExternalBaseClass(metadata)) {
                        facts.add(typeFact("Interface", name, metadata));
                    }
                }
                case FUNCTION, METHOD -> {
                    String entry = entryFact(node, metadata);
                    if (entry != null) {
                        facts.add(entry);
                    }
                }
                default -> {}
            }
        }
        return facts.stream().distinct().toList();
    }

    private static boolean hasExternalBaseClass(JsonNode metadata) {
        JsonNode bases = metadata.path("base_classes");
        return bases.isArray() && !bases.isEmpty();
    }

    private static String typeFact(String kind, String name, JsonNode metadata) {
        JsonNode bases = metadata.path("base_classes");
        if (bases.isArray() && !bases.isEmpty()) {
            List<String> baseNames = new ArrayList<>();
            bases.forEach(item -> {
                String text = item.asText("");
                if (!text.isBlank() && baseNames.size() < 2) {
                    baseNames.add(text);
                }
            });
            if (!baseNames.isEmpty()) {
                return "symbol=" + kind + ":" + name + " extends " + String.join("|", baseNames);
            }
        }
        return "symbol=" + kind + ":" + name;
    }

    private static String entryFact(CtxNode node, JsonNode metadata) {
        if (!metadata.path("is_entry_point").asBoolean(false)) {
            return null;
        }
        String kind = metadata.path("cmmLabel").asText(node.getNodeType().name());
        StringBuilder entry =
                new StringBuilder("entry=").append(kind).append(':').append(node.getSymbolName());
        String returnType = metadata.path("return_type").asText("");
        if (!returnType.isBlank()) {
            entry.append(" returns=").append(returnType);
        }
        JsonNode parameterNames = metadata.path("param_names");
        if (parameterNames.isArray() && !parameterNames.isEmpty()) {
            List<String> names = new ArrayList<>();
            parameterNames.forEach(parameter -> names.add(parameter.asText()));
            entry.append(" params=").append(String.join(",", names));
        }
        return entry.toString();
    }

    private static String language(String path, List<CtxNode> nodes) {
        for (CtxNode node : nodes) {
            if (node.getNodeType() == NodeType.FILE) {
                String language = metadata(node).path("language").asText("");
                if (!language.isBlank()) {
                    return language;
                }
            }
        }
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    private static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : ".";
    }

    private static JsonNode metadata(CtxNode node) {
        String metadata = node.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(metadata);
        } catch (Exception e) {
            return JSON.missingNode();
        }
    }
}
