package com.kratisai.controlplane.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Heuristic validator for Mermaid diagrams embedded in LLM-generated Markdown (wiki pages,
 * canvas documents, chat responses).
 *
 * <p>This does not implement the full Mermaid grammar. It targets the most common failure modes
 * observed from LLM-generated diagrams:
 *
 * <ul>
 *   <li>Invalid or unrecognized diagram type keywords
 *   <li>Unquoted subgraph titles containing special characters (flowchart)
 *   <li>Unquoted node labels containing shape-delimiter characters (flowchart)
 *   <li>Unquoted edge labels containing special characters (flowchart)
 *   <li>Missing {@code end} keywords for subgraphs (flowchart)
 *   <li>Missing {@code end} keywords for sequence diagram blocks (sequenceDiagram)
 *   <li>Unbalanced braces in class/state diagram blocks
 * </ul>
 *
 * <p>Each rule is applied per-diagram (per fenced {@code ```mermaid} block), so multiple diagrams
 * in the same markdown are validated independently and line numbers are relative to each block.
 */
@Component
public class MermaidDiagramValidator {

    private static final Pattern MERMAID_BLOCK = Pattern.compile("```mermaid\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern UNSAFE_BARE_TITLE_CHARS = Pattern.compile("[()\\[\\]{}:;#\"']");
    private static final Pattern EDGE_PIPE_LABEL =
            Pattern.compile("(?:[-=.~]+[>oox]?|[<oox]?[-=.~]+|[-=.~]+)\\s*\\|([^|\\r\\n]+)\\|");
    private static final Pattern SUBGRAPH_LINE = Pattern.compile("^\\s*subgraph\\s+(.+?)\\s*$");
    private static final Pattern SEQUENCE_BLOCK_START =
            Pattern.compile("^\\s*(loop|alt|opt|par|rect|critical|break)\\b");
    private static final Pattern END_LINE = Pattern.compile("^\\s*end\\s*$");
    private static final Pattern BRACE_COUNT = Pattern.compile("[{}]");
    private static final Pattern NODE_START =
            Pattern.compile("\\b([\\w-]+)\\s*(\\[\\[|\\[\\(|\\(\\[|\\{\\{|\\(\\(|\\[/|\\[\\\\|\\[|\\{|\\()");
    private static final Map<String, String> NODE_SHAPE_CLOSERS = Map.of(
            "[[", "]]",
            "[(", ")]",
            "([", "])",
            "{{", "}}",
            "((", "))",
            "[/", "/]",
            "[\\", "\\]",
            "[", "]",
            "{", "}",
            "(", ")");
    private static final String UNSAFE_NODE_LABEL_CHARS = "()[]{}|";

    private static final List<String> VALID_DIAGRAM_TYPES = List.of(
            "graph",
            "flowchart",
            "sequenceDiagram",
            "classDiagram",
            "stateDiagram",
            "stateDiagram-v2",
            "erDiagram",
            "gantt",
            "pie",
            "mindmap",
            "gitGraph",
            "journey",
            "timeline",
            "sankey",
            "quadrantChart",
            "xychart-beta",
            "block-beta",
            "packet-beta",
            "kanban",
            "architecture");

    /**
     * Scans the given Markdown content for fenced {@code ```mermaid} blocks and returns a
     * human-readable description of any syntax issues found. An empty list means no issues were
     * detected (which does not guarantee the diagram is valid, only that no known bad patterns
     * were found).
     */
    public List<String> findIssues(String markdownContent) {
        List<String> issues = new ArrayList<>();
        if (markdownContent == null || markdownContent.isBlank()) {
            return issues;
        }

        Matcher blockMatcher = MERMAID_BLOCK.matcher(markdownContent);
        int diagramIndex = 0;
        while (blockMatcher.find()) {
            diagramIndex++;
            String block = blockMatcher.group(1);
            issues.addAll(findIssuesInBlock(block, diagramIndex));
        }
        return issues;
    }

    private List<String> findIssuesInBlock(String block, int diagramIndex) {
        List<String> issues = new ArrayList<>();
        String[] lines = block.split("\n", -1);

        String diagramType = detectDiagramType(lines);
        if (diagramType == null) {
            issues.add("Mermaid diagram #%d: no valid diagram type keyword found. Expected one of: %s."
                    .formatted(diagramIndex, String.join(", ", VALID_DIAGRAM_TYPES)));
            return issues;
        }

        switch (diagramType) {
            case "graph", "flowchart" -> issues.addAll(checkFlowchartRules(lines, diagramIndex));
            case "sequenceDiagram" -> issues.addAll(checkSequenceDiagramRules(lines, diagramIndex));
            case "classDiagram", "stateDiagram", "stateDiagram-v2" ->
                issues.addAll(checkBraceBalanceRules(lines, diagramIndex));
            default -> {}
        }

        return issues;
    }

    private String detectDiagramType(String[] lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("%%")) {
                continue;
            }
            String keyword = trimmed.replaceFirst(";.*$", "").trim().split("\\s+")[0];
            for (String type : VALID_DIAGRAM_TYPES) {
                if (keyword.equals(type)) {
                    return type;
                }
            }
            return null;
        }
        return null;
    }

    private List<String> checkFlowchartRules(String[] lines, int diagramIndex) {
        List<String> issues = new ArrayList<>();
        int subgraphCount = 0;
        int endCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isCommentLine(line)) {
                continue;
            }

            Matcher subgraphMatcher = SUBGRAPH_LINE.matcher(line);
            if (subgraphMatcher.matches()) {
                subgraphCount++;
                String title = subgraphMatcher.group(1);
                if (isUnsafeSubgraphTitle(title)) {
                    issues.add(
                            "Mermaid diagram #%d, line %d: subgraph title \"%s\" contains characters Mermaid will misparse as node-shape syntax. Quote it, e.g. subgraph \"%s\"."
                                    .formatted(diagramIndex, i + 1, title, title));
                }
            } else {
                issues.addAll(checkEdgeLabelRules(line, diagramIndex, i + 1));
                String lineWithoutEdgeLabels = EDGE_PIPE_LABEL.matcher(line).replaceAll("");
                issues.addAll(checkNodeLabelRules(lineWithoutEdgeLabels, diagramIndex, i + 1));
            }

            if (END_LINE.matcher(line).matches()) {
                endCount++;
            }
        }

        if (subgraphCount > endCount) {
            issues.add(
                    "Mermaid diagram #%d: found %d subgraph(s) but only %d 'end' keyword(s). Each subgraph must be closed with 'end'."
                            .formatted(diagramIndex, subgraphCount, endCount));
        }

        return issues;
    }

    private List<String> checkSequenceDiagramRules(String[] lines, int diagramIndex) {
        int blockStartCount = 0;
        int endCount = 0;

        for (String line : lines) {
            if (isCommentLine(line)) {
                continue;
            }
            if (SEQUENCE_BLOCK_START.matcher(line).find()) {
                blockStartCount++;
            }
            if (END_LINE.matcher(line).matches()) {
                endCount++;
            }
        }

        if (blockStartCount > endCount) {
            return List.of(
                    "Mermaid diagram #%d: found %d sequence block(s) (loop/alt/opt/par/rect/critical/break) but only %d 'end' keyword(s). Each block must be closed with 'end'."
                            .formatted(diagramIndex, blockStartCount, endCount));
        }
        return List.of();
    }

    private List<String> checkBraceBalanceRules(String[] lines, int diagramIndex) {
        int openCount = 0;
        int closeCount = 0;

        for (String line : lines) {
            if (isCommentLine(line)) {
                continue;
            }
            Matcher braceMatcher = BRACE_COUNT.matcher(line);
            while (braceMatcher.find()) {
                if (braceMatcher.group().equals("{")) {
                    openCount++;
                } else {
                    closeCount++;
                }
            }
        }

        if (openCount != closeCount) {
            return List.of(
                    "Mermaid diagram #%d: unbalanced braces — found %d '{' but %d '}'. Each opening brace must have a matching closing brace."
                            .formatted(diagramIndex, openCount, closeCount));
        }
        return List.of();
    }

    private boolean isCommentLine(String line) {
        return line.trim().startsWith("%%");
    }

    private List<String> checkNodeLabelRules(String line, int diagramIndex, int lineNumber) {
        List<String> issues = new ArrayList<>();
        Matcher matcher = NODE_START.matcher(line);
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String closer = NODE_SHAPE_CLOSERS.get(matcher.group(2));
            if (closer == null) {
                continue;
            }
            String label = extractNodeLabel(line, matcher.end(), closer);
            if (hasUnquotedShapeDelimiter(label)) {
                issues.add(
                        "Mermaid diagram #%d, line %d: node \"%s\" label \"%s\" is not quoted and contains characters Mermaid will misparse as node-shape syntax. Wrap the label in double quotes."
                                .formatted(diagramIndex, lineNumber, nodeId, label.trim()));
            }
        }
        return issues;
    }

    /**
     * Returns the node label between a shape opener and its closing delimiter, skipping over
     * double-quoted sections so that a quoted label may legitimately contain the delimiter.
     */
    private String extractNodeLabel(String text, int labelStart, String closer) {
        int i = labelStart;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                int quoteEnd = text.indexOf('"', i + 1);
                if (quoteEnd < 0) {
                    break;
                }
                i = quoteEnd + 1;
                continue;
            }
            if (text.startsWith(closer, i)) {
                return text.substring(labelStart, i);
            }
            i++;
        }
        return text.substring(labelStart);
    }

    /**
     * A label is unsafe when it contains a shape-delimiter character outside of a double-quoted
     * section; Mermaid misparses such characters as the start of a nested shape.
     */
    private boolean hasUnquotedShapeDelimiter(String label) {
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '"') {
                int quoteEnd = label.indexOf('"', i + 1);
                if (quoteEnd < 0) {
                    return false;
                }
                i = quoteEnd;
                continue;
            }
            if (UNSAFE_NODE_LABEL_CHARS.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private List<String> checkEdgeLabelRules(String line, int diagramIndex, int lineNumber) {
        List<String> issues = new ArrayList<>();
        Matcher matcher = EDGE_PIPE_LABEL.matcher(line);
        while (matcher.find()) {
            String label = matcher.group(1);
            if (isUnsafeEdgeLabel(label)) {
                issues.add(
                        "Mermaid diagram #%d, line %d: edge label \"%s\" is not quoted and contains characters Mermaid will misparse as syntax delimiters. Wrap the label in double quotes, e.g. -->|\"%s\"|."
                                .formatted(diagramIndex, lineNumber, label.trim(), label.trim()));
            }
        }
        return issues;
    }

    private boolean isUnsafeEdgeLabel(String label) {
        String trimmed = label.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return false;
        }
        return UNSAFE_BARE_TITLE_CHARS.matcher(trimmed).find();
    }

    /**
     * A subgraph title is unsafe when a bare title (no leading quote) contains a character that
     * Mermaid's flowchart grammar treats specially, or when a bracketed title's label contains an
     * unquoted shape delimiter.
     */
    private boolean isUnsafeSubgraphTitle(String title) {
        String trimmed = title.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            return false;
        }
        int bracket = trimmed.indexOf('[');
        if (bracket >= 0) {
            return hasUnquotedShapeDelimiter(extractNodeLabel(trimmed, bracket + 1, "]"));
        }
        return UNSAFE_BARE_TITLE_CHARS.matcher(trimmed).find();
    }
}
