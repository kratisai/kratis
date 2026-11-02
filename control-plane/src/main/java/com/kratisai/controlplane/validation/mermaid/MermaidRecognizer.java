package com.kratisai.controlplane.validation.mermaid;

import com.kratisai.controlplane.validation.mermaid.MermaidGrammar.FlowShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MermaidRecognizer {
    private static final String ID = "[A-Za-z][A-Za-z0-9_]*";
    private static final String NUM = "-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)";
    private static final Pattern ENTITY = Pattern.compile("#quot;|#(\\d+);");
    private static final Pattern BREAK = Pattern.compile("<br\\s*/?>|\\\\n", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY = Pattern.compile("&[#A-Za-z0-9]");
    private static final Pattern RAW_FORBIDDEN =
            Pattern.compile("[\"<>\\r\\n\\x00-\\x1f]|#(?:quot|\\d+);|&[#A-Za-z0-9]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOW_HEADER = Pattern.compile("^(flowchart|graph)(?: (?:TD|TB|BT|LR|RL))?$");
    private static final Pattern FLOW_AMBIGUOUS = Pattern.compile("[\"\\[\\]{}|]");
    private static final Pattern CARDINALITY = Pattern.compile("^(?:\\|\\||\\|o|o\\||}o|o\\{|}\\||\\|\\{)");
    private static final Pattern KEY = Pattern.compile("^(?:PK|FK|UK)(?:,(?:PK|FK|UK))*");
    private static final Pattern ER_TYPE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(?:\\([0-9]+(?:,[0-9]+)?\\))?(?:\\[\\])*");
    private static final Pattern C4_NAME = Pattern.compile("^[A-Za-z_]+");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^" + NUM);
    private static final Pattern SEQUENCE_SEPARATOR = Pattern.compile(
            ";\\s*(?:[A-Za-z][A-Za-z0-9_]*\\s*(?:-->>|->>|-->|->|--\\)|-\\)|--x|-x|<<--|<<-|<--|<-)|(?:participant|actor|create|destroy|alt|loop|opt|par|par_over|else|and|rect|box|critical|option|break|end|note|activate|deactivate|title|links|link|properties|details|autonumber)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_SEPARATOR = Pattern.compile(
            ";\\s*(?:(?:class|classDef|namespace|style|link|click|note|direction)\\b|[A-Za-z][A-Za-z0-9_]*[^;\\n]*?(?:<\\|--|\\*--|o--|-->|\\.\\.>|\\.\\.\\|>|<\\|\\.\\.|<\\.\\.|o\\.\\.|\\.\\.o|--))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATE_SEPARATOR = Pattern.compile(
            ";\\s*(?:(?:\\[\\*\\]|[A-Za-z][A-Za-z0-9_]*)\\s*-->|(?:state|note|class|classDef|direction|end)(?:\\s|$))");
    private static final Pattern JOURNEY_SEPARATOR =
            Pattern.compile(";\\s*(?:(?:title|section)\\s|[^:;]+:\\s*-?\\d+\\s*:)");
    private static final Pattern ENTITY_PREFIX = Pattern.compile("#(?:quot|\\d+)$");
    private static final Map<String, int[]> C4_ELEMENTS = Map.ofEntries(
            Map.entry("Boundary", new int[] {2, 3}),
            Map.entry("Component", new int[] {2, 4}),
            Map.entry("Component_Ext", new int[] {2, 4}),
            Map.entry("Container", new int[] {2, 4}),
            Map.entry("Container_Boundary", new int[] {2, 2}),
            Map.entry("Container_Ext", new int[] {2, 4}),
            Map.entry("Person", new int[] {2, 3}),
            Map.entry("Person_Ext", new int[] {2, 3}),
            Map.entry("Rel", new int[] {3, 4}),
            Map.entry("Rel_Neighbor", new int[] {3, 4}),
            Map.entry("System", new int[] {2, 3}),
            Map.entry("System_Boundary", new int[] {2, 2}),
            Map.entry("System_Ext", new int[] {2, 3}));
    private static final ThreadLocal<LineCursor> LINE_CURSOR = new ThreadLocal<>();
    private final MermaidGrammar grammar;

    private record FlowLabel(String close, String label) {}

    private record FlowGroup(String id, String render) {}

    private static final class LineCursor {
        private final String[] lines;
        private int next;
        private int current = 1;

        LineCursor(String source) {
            lines = source.split("\n", -1);
        }

        void advance(String line) {
            while (next < lines.length) {
                current = ++next;
                if (trim(lines[current - 1]).equals(line)) return;
            }
        }
    }

    private static void advance(String line) {
        LINE_CURSOR.get().advance(line);
    }

    public MermaidRecognizer(MermaidGrammar grammar) {
        this.grammar = Objects.requireNonNull(grammar);
    }

    public MermaidRecognition recognize(Object input) {
        try {
            if (!(input instanceof String)) fail("input.type", "source must be a string");
            String source = (String) input;
            if (source.length() > grammar.maxInputUtf16Units) fail("input.length", "invalid source size");
            if (Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]")
                    .matcher(source)
                    .find()) fail("input.control", "invalid control character");
            String normalized = source.replace("\r\n", "\n");
            if (normalized.contains("\r")) fail("input.control", "invalid control character");
            List<String> lines = new ArrayList<>();
            for (String line : normalized.split("\n", -1)) {
                String trimmed = trim(line);
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
            if (lines.isEmpty()) fail("input.header", "missing header");
            String header = lines.removeFirst();
            LINE_CURSOR.set(new LineCursor(normalized));
            advance(header);
            String type = FLOW_HEADER.matcher(header).matches() ? "flowchart" : header;
            if (!grammar.headers.containsKey(type)
                    || (!type.equals("flowchart") && !grammar.headers.get(type).contains(header)))
                fail(
                        "input.header",
                        "unsupported header; supported: " + String.join(", ", new TreeSet<>(grammar.headers.keySet())));
            if (lines.isEmpty()) fail("input.empty-diagram", "empty diagram");
            List<String> output =
                    switch (type) {
                        case "flowchart" -> flow(lines, header);
                        case "sequenceDiagram" -> sequence(lines, header);
                        case "classDiagram" -> classDiagram(lines, header);
                        case "stateDiagram-v2" -> state(lines, header);
                        case "erDiagram" -> er(lines, header);
                        case "C4Context", "C4Container", "C4Component" -> c4(lines, header);
                        case "pie" -> pie(lines, header);
                        case "quadrantChart" -> quadrant(lines, header);
                        case "xychart-beta" -> xy(lines, header);
                        case "journey" -> journey(lines, header);
                        default -> throw new IllegalStateException("unsupported grammar header: " + type);
                    };
            return new MermaidRecognition(true, type, source, String.join("\n", output), null, null);
        } catch (RecognitionFailure failure) {
            LineCursor cursor = LINE_CURSOR.get();
            int line = cursor == null ? 1 : cursor.current;
            String statement = cursor == null || cursor.lines.length < line ? "" : trim(cursor.lines[line - 1]);
            return new MermaidRecognition(
                    false,
                    null,
                    null,
                    null,
                    failure.rule,
                    "line " + line + ": " + failure.getMessage()
                            + (statement.isEmpty() ? "" : " near `" + statement + "`"));
        } finally {
            LINE_CURSOR.remove();
        }
    }

    private static void fail(String rule, String message) {
        throw new RecognitionFailure(rule, message);
    }

    private static void fail(String message) {
        fail("syntax.statement", message);
    }

    private static final class RecognitionFailure extends RuntimeException {
        private final String rule;

        RecognitionFailure(String rule, String message) {
            super(message);
            this.rule = rule;
        }
    }

    private static String trim(String value) {
        return value.replaceAll(
                "^[\\s\\x{00a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}]+|[\\s\\x{00a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}\\x{feff}]+$",
                "");
    }

    private static String text(String value) {
        String result = trim(value);
        if (result.isEmpty()) fail("empty text slot");
        return result;
    }

    private static String decode(String value) {
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) == null) replacement = "\"";
            else {
                double numeric = Double.parseDouble(matcher.group(1));
                if (numeric > 0x10ffff
                        || (numeric >= 0xd800 && numeric <= 0xdfff)
                        || (numeric < 0x20 && numeric != 9 && numeric != 10)
                        || numeric == 0x7f) fail("invalid character entity");
                replacement = new String(Character.toChars((int) numeric));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String bareText(String value) {
        String content = text(value);
        if (content.startsWith("\"")) {
            if (!content.endsWith("\"")
                    || content.length() < 2
                    || content.substring(1, content.length() - 1).contains("\"")) fail("ambiguous quoted text");
            return text(content.substring(1, content.length() - 1));
        }
        if (content.chars().filter(c -> c == '"').count() % 2 != 0) fail("unmatched text quote");
        return content;
    }

    private static String encodedText(String value) {
        String content = text(value);
        if (HTML_ENTITY.matcher(content).find()) fail("HTML-like entity is unsupported");
        String decoded = BREAK.matcher(decode(content)).replaceAll("\n");
        StringBuilder result = new StringBuilder();
        decoded.codePoints().forEach(code -> {
            if (code == '\n') result.append("<br/>");
            else if ((code >= 'A' && code <= 'Z')
                    || (code >= 'a' && code <= 'z')
                    || (code >= '0' && code <= '9')
                    || " .,!?$%*+=_-".indexOf(code) >= 0) result.appendCodePoint(code);
            else if (code == '"') result.append("#quot;");
            else result.append('#').append(code).append(';');
        });
        return text(result.toString());
    }

    private static String quoted(String value) {
        return "\"" + encodedText(value) + "\"";
    }

    private static String bare(String value) {
        return encodedText(bareText(value));
    }

    private static String raw(String value) {
        String content = text(value);
        if (RAW_FORBIDDEN.matcher(content).find()) fail("c4.raw-text", "unsafe C4 raw text");
        return "\"" + content + "\"";
    }

    private static String structuralText(String value, Pattern statements) {
        String content = text(value);
        if (!content.startsWith("\"")) {
            Matcher matcher = statements.matcher(content);
            while (matcher.find()) {
                if (!ENTITY_PREFIX
                        .matcher(content.substring(0, matcher.start()))
                        .find()) fail("text.statement-separator", "ambiguous multiple statements on one line");
            }
        }
        return bare(content);
    }

    private String identifier(String value, String reserved) {
        if (!grammar.identifier.matcher(value).matches()) fail("invalid identifier");
        if (reserved != null && grammar.reserved.get(reserved).contains(value.toLowerCase(Locale.ROOT)))
            fail("identifier.reserved", "reserved identifier");
        return value;
    }

    private String flowIdentifier(String value) {
        if (!grammar.flowIdentifier.matcher(value).matches()) fail("invalid flowchart identifier");
        if (grammar.reserved.get("flowchart").contains(value) || value.equals("classDef") || value.equals("linkStyle"))
            fail("identifier.reserved", "reserved identifier");
        return value;
    }

    private String number(String value) {
        if (!grammar.number.matcher(value).matches() || !Double.isFinite(Double.parseDouble(value)))
            fail("invalid number");
        return value;
    }

    private void depth(int value) {
        if (value > grammar.maxDepth) fail("input.depth", "maximum nesting depth exceeded");
    }

    private final class Scanner {
        final String line;
        int pos;

        Scanner(String line) {
            this.line = line;
        }

        void space() {
            while (pos < line.length() && (line.charAt(pos) == ' ' || line.charAt(pos) == '\t')) pos++;
        }

        void done() {
            space();
            if (pos != line.length()) fail("unexpected input");
        }

        boolean maybe(String token) {
            space();
            if (!line.startsWith(token, pos)) return false;
            pos += token.length();
            return true;
        }

        void literal(String token) {
            if (!maybe(token)) fail("expected " + token);
        }

        String take(Pattern pattern) {
            space();
            Matcher matcher = pattern.matcher(line.substring(pos));
            if (!matcher.find() || matcher.start() != 0) fail("expected token");
            String result = matcher.group();
            pos += result.length();
            return result;
        }

        String id() {
            return identifier(take(grammar.identifierPrefix), null);
        }

        String quoted() {
            if (!maybe("\"")) fail("expected a quoted label, e.g. \"Label\"");
            int end = line.indexOf('"', pos);
            if (end < 0) fail("unterminated quoted text");
            String result = line.substring(pos, end);
            pos = end + 1;
            return text(result);
        }
    }

    private List<String> flow(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header.replaceFirst("^graph", "flowchart")));
        List<String> stack = new ArrayList<>();
        List<Set<String>> scopedEndpoints = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        Set<String> nodes = new HashSet<>();
        int completedGroups = 0;
        for (String line : lines) {
            advance(line);
            Scanner s = new Scanner(line);
            if (s.maybe("subgraph ")) {
                FlowGroup group = flowGroup(s);
                if (!group.id().isEmpty()) addGroup(group.id(), groups, nodes);
                stack.add(group.id());
                scopedEndpoints.add(new HashSet<>());
                depth(stack.size());
                out.add(group.render());
            } else if (line.equals("end")) {
                if (stack.isEmpty()) fail("unmatched end; close each subgraph with end");
                String explicit = stack.removeLast();
                String id = explicit.isEmpty() ? "subGraph" + completedGroups : explicit;
                if (scopedEndpoints.removeLast().contains(id))
                    fail("flow.subgraph-id-unique", "subgraph cannot contain itself as an edge endpoint");
                if (explicit.isEmpty()) addGroup(id, groups, nodes);
                completedGroups++;
                out.add("end");
            } else {
                String first = flowNode(s);
                StringBuilder result = new StringBuilder(first);
                String firstId = flowPrefixId(first);
                if (!first.equals(firstId)) nodes.add(firstId);
                s.space();
                boolean standalone = s.pos == line.length();
                List<String> endpoints = new ArrayList<>(List.of(firstId));
                while (s.pos < line.length()) {
                    String arrow = null;
                    for (String candidate : grammar.flowArrows) {
                        if (s.line.startsWith(candidate, s.pos)) {
                            arrow = candidate;
                            break;
                        }
                    }
                    if (arrow == null) fail("expected an arrow such as -->, e.g. A --> B");
                    s.pos += arrow.length();
                    String label =
                            s.maybe("|") ? "|" + flowLabel(s, List.of("|")).label() + "|" : "";
                    String to = flowNode(s);
                    String toId = flowPrefixId(to);
                    endpoints.add(toId);
                    if (!to.equals(toId)) nodes.add(toId);
                    result.append(' ').append(arrow).append(label).append(' ').append(to);
                    s.space();
                }
                if (standalone) nodes.add(firstId);
                for (Set<String> scope : scopedEndpoints) scope.addAll(endpoints);
                if (endpoints.stream().anyMatch(stack::contains))
                    fail("flow.subgraph-id-unique", "subgraph cannot contain itself as an edge endpoint");
                out.add(result.toString());
            }
        }
        if (!stack.isEmpty()) fail("unclosed subgraph; add end");
        if (groups.stream().anyMatch(nodes::contains))
            fail("flow.subgraph-id-unique", "subgraph id conflicts with node");
        return out;
    }

    private static void addGroup(String id, Set<String> groups, Set<String> nodes) {
        if (groups.contains(id) || nodes.contains(id))
            fail("flow.subgraph-id-unique", "duplicate or conflicting subgraph id");
        groups.add(id);
    }

    private List<String> pie(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        int slices = 0;
        boolean title = false;
        for (String line : lines) {
            advance(line);
            if (line.startsWith("title ")) {
                if (title || slices > 0) fail("duplicate or misplaced pie title");
                title = true;
                out.add("title " + bare(line.substring(6)));
            } else {
                Scanner s = new Scanner(line);
                String label = s.quoted();
                s.literal(":");
                String value = number(s.take(NUMBER_PREFIX));
                s.done();
                if (Double.parseDouble(value) < 0) fail("negative pie value");
                slices++;
                out.add(quoted(label) + " : " + value);
            }
        }
        if (slices == 0) fail("pie requires a slice");
        return out;
    }

    private List<String> quadrant(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        Set<String> captions = new HashSet<>();
        int points = 0;
        for (String line : lines) {
            advance(line);
            Matcher m;
            if ((m = match("^title (.+)$", line)) != null) {
                if (!captions.add("title") || points > 0) fail("duplicate or misplaced title");
                out.add("title " + bare(m.group(1)));
            } else if ((m = match("^(x-axis|y-axis) \"([^\"]+)\" --> \"([^\"]+)\"$", line)) != null) {
                if (!captions.add(m.group(1)) || points > 0) fail("duplicate or misplaced axis");
                out.add(m.group(1) + " " + quoted(m.group(2)) + " --> " + quoted(m.group(3)));
            } else if ((m = match("^(quadrant-[1-4]) \"([^\"]+)\"$", line)) != null) {
                if (!captions.add(m.group(1)) || points > 0) fail("duplicate or misplaced quadrant");
                out.add(m.group(1) + " " + quoted(m.group(2)));
            } else if ((m = match("^\"([^\"]+)\": \\[(" + NUM + "),\\s*(" + NUM + ")\\]$", line)) != null) {
                if (!grammar.coordinate.matcher(m.group(2)).matches()
                        || !grammar.coordinate.matcher(m.group(3)).matches()
                        || !Double.isFinite(Double.parseDouble(m.group(2)))
                        || !Double.isFinite(Double.parseDouble(m.group(3)))) fail("invalid quadrant coordinate");
                points++;
                out.add(quoted(m.group(1)) + ": [" + m.group(2) + ", " + m.group(3) + "]");
            } else
                fail(
                        "unsupported quadrant statement; allowed: title, x-axis/y-axis \"low\" --> \"high\", quadrant-1..4 \"caption\", \"point\": [x, y]");
        }
        if (!captions.containsAll(List.of("x-axis", "y-axis", "quadrant-1", "quadrant-2", "quadrant-3", "quadrant-4"))
                || points == 0) fail("quadrant requires axes, four captions and a point");
        return out;
    }

    private List<String> xy(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        int categories = 0;
        int series = 0;
        boolean title = false;
        boolean yAxis = false;
        for (String line : lines) {
            advance(line);
            Matcher m;
            if ((m = match("^title \"([^\"]+)\"$", line)) != null) {
                if (title || categories > 0 || series > 0 || yAxis) fail("duplicate or misplaced title");
                title = true;
                out.add("title " + quoted(m.group(1)));
            } else if (line.startsWith("x-axis ")) {
                if (categories > 0 || series > 0 || yAxis) fail("duplicate or misplaced x-axis");
                Scanner s = new Scanner(line.substring(7));
                String label = "";
                s.space();
                if (s.pos < s.line.length() && s.line.charAt(s.pos) == '"') label = quoted(s.quoted()) + " ";
                s.literal("[");
                List<String> values = new ArrayList<>(List.of(s.quoted()));
                while (s.maybe(",")) values.add(s.quoted());
                s.literal("]");
                s.done();
                categories = values.size();
                out.add("x-axis " + label + "["
                        + String.join(
                                ", ",
                                values.stream().map(MermaidRecognizer::quoted).toList()) + "]");
            } else if ((m = match("^y-axis \"([^\"]+)\" (" + NUM + ") --> (" + NUM + ")$", line)) != null) {
                if (yAxis
                        || series > 0
                        || categories == 0
                        || Double.parseDouble(number(m.group(2))) >= Double.parseDouble(number(m.group(3))))
                    fail("invalid y-axis");
                yAxis = true;
                out.add("y-axis " + quoted(m.group(1)) + " " + m.group(2) + " --> " + m.group(3));
            } else if ((m = match("^(bar|line) \\[([^\\]]+)\\]$", line)) != null) {
                if (categories == 0) fail("series requires categories");
                List<String> values = Arrays.stream(m.group(2).split(",", -1))
                        .map(MermaidRecognizer::trim)
                        .map(this::number)
                        .toList();
                if (values.size() != categories) fail("xy.series-length", "series length mismatch");
                series++;
                out.add(m.group(1) + " [" + String.join(", ", values) + "]");
            } else
                fail(
                        "unsupported xychart statement; allowed: title, x-axis [\"category\"], y-axis \"label\" [min --> max], bar/line [values]");
        }
        if (categories == 0 || series == 0) fail("xychart requires categories and a series");
        return out;
    }

    private List<String> journey(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        boolean title = false;
        boolean section = false;
        int tasks = 0;
        for (String line : lines) {
            advance(line);
            Matcher m;
            if ((m = match("^title (.+)$", line)) != null) {
                if (title || section) fail("duplicate or misplaced journey title");
                title = true;
                out.add("title " + structuralText(m.group(1), JOURNEY_SEPARATOR));
            } else if ((m = match("^section (.+)$", line)) != null) {
                section = true;
                out.add("section " + structuralText(m.group(1), JOURNEY_SEPARATOR));
            } else if ((m = match("^(\"[^\"]*\"|[^:]+):\\s*(-?\\d+)\\s*:\\s*(.+)$", line)) != null) {
                if (!section) fail("journey task requires section");
                double score = Double.parseDouble(m.group(2));
                if (!Double.isFinite(score)
                        || Math.abs(score) > 9007199254740991d
                        || score < grammar.journeyScoreMin
                        || score > grammar.journeyScoreMax) fail("journey.score", "journey score out of bounds");
                List<String> roles = new ArrayList<>();
                for (String role : splitRoles(m.group(3))) {
                    if (decode(bareText(role)).contains(",")) fail("comma inside journey role is unsupported");
                    roles.add(structuralText(role, JOURNEY_SEPARATOR));
                }
                tasks++;
                out.add(structuralText(m.group(1), JOURNEY_SEPARATOR) + ": " + (int) score + ": "
                        + String.join(", ", roles));
            } else fail("unsupported journey statement; allowed: title, section, task with score and roles");
        }
        if (tasks == 0) fail("journey requires a task");
        return out;
    }

    private static List<String> splitRoles(String value) {
        List<String> roles = new ArrayList<>();
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '"') quoted = !quoted;
            if (value.charAt(i) == ',' && !quoted) {
                roles.add(value.substring(start, i));
                start = i + 1;
            }
        }
        if (quoted) fail("unmatched role quote");
        roles.add(value.substring(start));
        return roles;
    }

    private String prefixId(String value) {
        Matcher matcher = grammar.identifierPrefix.matcher(value);
        if (!matcher.find()) throw new IllegalStateException("missing identifier");
        return matcher.group();
    }

    private String flowPrefixId(String value) {
        Matcher matcher = grammar.flowIdentifierPrefix.matcher(value);
        if (!matcher.find()) throw new IllegalStateException("missing flowchart identifier");
        return matcher.group();
    }

    private FlowGroup flowGroup(Scanner s) {
        s.space();
        int start = s.pos;
        String title;
        if (s.pos < s.line.length() && s.line.charAt(s.pos) == '"') {
            title = s.quoted();
            s.done();
        } else {
            if (!grammar.flowIdentifierPrefix.matcher(s.line.substring(s.pos)).find())
                fail("expected a subgraph, e.g. subgraph API[\"API Layer\"]");
            String id = s.take(grammar.flowIdentifierPrefix);
            if (s.maybe("[")) {
                String label = flowLabel(s, List.of("]")).label();
                s.done();
                flowIdentifier(id);
                return new FlowGroup(id, "subgraph " + id + "[" + label + "]");
            }
            s.space();
            if (s.pos == s.line.length()) {
                flowIdentifier(id);
                return new FlowGroup(id, "subgraph " + id + "[" + quoted(id) + "]");
            }
            title = flowBareLabel(s.line.substring(start));
        }
        if (grammar.flowIdentifier.matcher(title).matches()) {
            String id = flowIdentifier(title);
            return new FlowGroup(id, "subgraph " + id + "[" + quoted(title) + "]");
        }
        return new FlowGroup("", "subgraph " + quoted(title));
    }

    private static String flowBareLabel(String value) {
        String label = text(value);
        if (FLOW_AMBIGUOUS.matcher(label).find())
            fail("unsafe or ambiguous label; use plain text, e.g. A[\"API (v1)\"]");
        return label;
    }

    private FlowLabel flowLabel(Scanner s, List<String> closers) {
        s.space();
        String label;
        if (s.pos < s.line.length() && s.line.charAt(s.pos) == '"') {
            label = s.quoted();
        } else {
            int end = s.line.length() + 1;
            for (String close : closers) {
                int candidate = s.line.indexOf(close, s.pos);
                if (candidate >= 0) end = Math.min(end, candidate);
            }
            if (end > s.line.length())
                fail("missing label or closing shape; e.g. A[\"Service\"] or DB[(\"Database\")]");
            label = flowBareLabel(s.line.substring(s.pos, end));
            s.pos = end;
        }
        s.space();
        for (String close : closers) {
            if (s.line.startsWith(close, s.pos)) {
                s.pos += close.length();
                return new FlowLabel(close, quoted(label));
            }
        }
        throw new RecognitionFailure(
                "syntax.statement", "missing closing shape; e.g. A[\"Service\"] or DB[(\"Database\")]");
    }

    private String flowNode(Scanner s) {
        s.space();
        if (!grammar.flowIdentifierPrefix.matcher(s.line.substring(s.pos)).find())
            fail("expected a node ID such as A or DB");
        String id = flowIdentifier(s.take(grammar.flowIdentifierPrefix));
        s.space();
        for (FlowShape shape : grammar.flowShapes) {
            if (s.line.startsWith(shape.open(), s.pos)) {
                s.pos += shape.open().length();
                FlowLabel label = flowLabel(s, shape.closes());
                return id + shape.open() + label.label() + label.close();
            }
        }
        return id;
    }

    private static Matcher match(String regex, String value) {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        return matcher.matches() ? matcher : null;
    }

    private List<String> c4(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        List<Integer> stack = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> shapes = new HashSet<>();
        List<List<String>> relations = new ArrayList<>();
        boolean title = false;
        for (String line : lines) {
            advance(line);
            if (line.startsWith("title ")) {
                if (title) fail("duplicate C4 title");
                title = true;
                out.add("title " + bare(line.substring(6)));
                continue;
            }
            if (line.equals("}")) {
                if (stack.isEmpty()) fail("unmatched C4 boundary");
                if (stack.removeLast() == 0) fail("c4.boundary-nonempty", "empty C4 boundary");
                out.add("}");
                continue;
            }
            Scanner s = new Scanner(line);
            String name = s.take(C4_NAME);
            int[] limits = C4_ELEMENTS.get(name);
            if (limits == null)
                fail("unsupported C4 element; allowed: Person, System, Container, Component, boundaries, Rel");
            s.literal("(");
            List<String> args = new ArrayList<>(List.of(s.id()));
            boolean relation = name.startsWith("Rel");
            if (relation) {
                s.literal(",");
                args.add(s.id());
            }
            while (s.maybe(",")) args.add(raw(s.quoted()));
            s.literal(")");
            boolean boundary = name.endsWith("Boundary");
            boolean open = s.maybe("{");
            s.done();
            if (args.size() < limits[0] || args.size() > limits[1] || boundary != open)
                fail("invalid C4 arguments or boundary");
            if (relation) relations.add(List.of(args.get(0), args.get(1)));
            else {
                if (!ids.add(args.getFirst())) fail("c4.id-unique", "duplicate C4 identifier");
                if (!boundary) shapes.add(args.getFirst());
            }
            if (!stack.isEmpty()) stack.set(stack.size() - 1, stack.getLast() + 1);
            if (open) {
                stack.add(0);
                depth(stack.size());
            }
            out.add(name + "(" + String.join(", ", args) + ")" + (open ? " {" : ""));
        }
        if (!stack.isEmpty()) fail("unclosed C4 boundary");
        for (List<String> relation : relations)
            if (!shapes.contains(relation.get(0)) || !shapes.contains(relation.get(1)))
                fail("c4.endpoint-declared", "unknown C4 relation endpoint");
        return out;
    }

    private List<String> classDiagram(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        boolean body = false;
        for (String line : lines) {
            advance(line);
            Matcher m;
            if (body) {
                if (line.equals("}")) {
                    body = false;
                    out.add("}");
                    continue;
                }
                if (Pattern.compile("[{}]").matcher(line).find()) fail("nested class body");
                String trimmed = line.trim();
                if (trimmed.matches("<<[A-Za-z][A-Za-z0-9_-]*>>")) {
                    out.add(trimmed);
                    continue;
                }
                out.add(classMember(line));
            } else if ((m = match(
                            "^class (" + ID
                                    + ")(~[^~\\s][^~]*~)?(?:\\[\"([^\"]+)\"\\])?(<<[A-Za-z][A-Za-z0-9_-]*>>)?( \\{)?$",
                            line))
                    != null) {
                body = m.group(5) != null;
                out.add("class " + identifier(m.group(1), "classDiagram")
                        + (m.group(2) != null ? m.group(2) : "")
                        + (m.group(3) != null ? "[" + quoted(m.group(3)) + "]" : "")
                        + (m.group(4) != null ? m.group(4) : "")
                        + (body ? " {" : ""));
            } else if ((m = match("^note for (" + ID + ") (.+)$", line)) != null) {
                String raw = m.group(2);
                String note = raw.startsWith("\"") ? bareText(raw) : raw;
                out.add("note for " + identifier(m.group(1), "classDiagram") + " " + quoted(note));
            } else if ((m = match(
                            "^(" + ID
                                    + ")(?: \"([^\"]+)\")? (<\\|--|\\*--|o--|-->|\\.\\.>|\\.\\.\\|>|<\\|\\.\\.|<\\.\\.|o\\.\\.|\\.\\.o|--) (?:\"([^\"]+)\" )?("
                                    + ID + ")(?: : (.+))?$",
                            line))
                    != null) {
                out.add(identifier(m.group(1), "classDiagram") + (m.group(2) != null ? " " + quoted(m.group(2)) : "")
                        + " " + m.group(3) + " " + (m.group(4) != null ? quoted(m.group(4)) + " " : "")
                        + identifier(m.group(5), "classDiagram")
                        + (m.group(6) != null ? " : " + structuralText(m.group(6), CLASS_SEPARATOR) : ""));
            } else
                fail(
                        "unsupported class statement; allowed: class declaration/body, relation with <|-- *-- o-- --> ..> ..|> -- <|.. <.. o.. ..o, note for");
        }
        if (body) fail("unclosed class body");
        return out;
    }

    private static String classMember(String value) {
        String content = text(value.startsWith("\"") ? bareText(value) : value);
        if (Pattern.compile("[{}]").matcher(content).find()) fail("nested class body");
        if (HTML_ENTITY.matcher(content).find()) fail("HTML-like entity is unsupported");
        String decoded = BREAK.matcher(decode(content)).replaceAll("\n");
        if (decoded.contains(";")) fail("text.statement-separator", "ambiguous multiple statements on one line");
        StringBuilder result = new StringBuilder();
        for (String part : decoded.split("\n", -1)) if (!part.isBlank()) result.append(part.trim());
        StringBuilder encoded = new StringBuilder();
        result.toString().codePoints().forEach(code -> {
            if (code == '<') encoded.append("#60;");
            else if (code == '>') encoded.append("#62;");
            else if (code == '&') encoded.append("#38;");
            else if (code == '"') encoded.append("#quot;");
            else encoded.appendCodePoint(code);
        });
        return text(encoded.toString());
    }

    private List<String> er(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        boolean body = false;
        for (String line : lines) {
            advance(line);
            Scanner s = new Scanner(line);
            if (body) {
                if (line.equals("}")) {
                    body = false;
                    out.add("}");
                    continue;
                }
                String type = s.take(ER_TYPE);
                identifier(prefixId(type), "erAttribute");
                String name = identifier(s.id(), "erAttribute");
                String key = "";
                String comment = "";
                s.space();
                if (s.pos < line.length() && line.charAt(s.pos) != '"') {
                    key = s.take(KEY);
                    List<String> keys = Arrays.asList(key.split(","));
                    if (new HashSet<>(keys).size() != keys.size()) fail("duplicate attribute key");
                }
                s.space();
                if (s.pos < line.length()) comment = " " + quoted(s.quoted());
                s.done();
                out.add(type + " " + name + (key.isEmpty() ? "" : " " + key) + comment);
            } else {
                String from = erName(s);
                if (s.maybe("{")) {
                    s.done();
                    body = true;
                    out.add(from + " {");
                    continue;
                }
                String left = s.take(CARDINALITY);
                s.literal("--");
                String right = s.take(CARDINALITY);
                String to = erName(s);
                s.literal(":");
                s.space();
                boolean isQuoted = s.pos < s.line.length() && s.line.charAt(s.pos) == '"';
                String label = isQuoted ? s.quoted() : text(s.line.substring(s.pos));
                if (!isQuoted) {
                    for (int i = 0; i < label.length(); i++) {
                        if (label.charAt(i) == ';'
                                && !ENTITY_PREFIX.matcher(label.substring(0, i)).find())
                            fail("text.statement-separator", "ambiguous multiple statements on one line");
                    }
                    s.pos = s.line.length();
                }
                s.done();
                out.add(from + " " + left + "--" + right + " " + to + " : " + quoted(label));
            }
        }
        if (body) fail("unclosed ER body");
        return out;
    }

    private List<String> sequence(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        List<String> stack = new ArrayList<>();
        Set<String> participants = new HashSet<>();
        Set<String> declarations = new HashSet<>();
        Set<String> references = new HashSet<>();
        Map<String, Integer> active = new HashMap<>();
        for (String line : lines) {
            advance(line);
            Matcher m;
            if (line.equals("autonumber")) {
                out.add(line);
            } else if ((m = match("^(participant|actor) (" + ID + ")(?: as (.+))?$", line)) != null) {
                String id = identifier(m.group(2), "sequenceDiagram");
                if (!declarations.add(id)) fail("sequence.declaration-unique", "duplicate sequence declaration");
                participants.add(id);
                out.add(m.group(1) + " " + id
                        + (m.group(3) != null ? " as " + structuralText(m.group(3), SEQUENCE_SEPARATOR) : ""));
            } else if ((m = match("^(alt|loop|opt|par) (.+)$", line)) != null) {
                stack.add(m.group(1));
                depth(stack.size());
                out.add(m.group(1) + " " + structuralText(m.group(2), SEQUENCE_SEPARATOR));
            } else if ((m = match("^(else|and) (.+)$", line)) != null) {
                String expected = m.group(1).equals("else") ? "alt" : "par";
                if (stack.isEmpty() || !stack.getLast().equals(expected))
                    fail("sequence.branch", "invalid sequence branch");
                out.add(m.group(1) + " " + structuralText(m.group(2), SEQUENCE_SEPARATOR));
            } else if (line.equals("end")) {
                if (stack.isEmpty()) fail("unmatched end");
                stack.removeLast();
                out.add("end");
            } else if ((m = match("^title (.+)$", line)) != null) {
                out.add("title " + structuralText(m.group(1), SEQUENCE_SEPARATOR));
            } else if ((m = match("^Note (over|left of|right of) (" + ID + ")(?:,(" + ID + "))?: (.+)$", line))
                    != null) {
                if (m.group(3) != null && !m.group(1).equals("over")) fail("invalid note participants");
                String first = identifier(m.group(2), "sequenceDiagram");
                String second = m.group(3) != null ? identifier(m.group(3), "sequenceDiagram") : null;
                participants.add(first);
                if (second != null) participants.add(second);
                out.add("Note " + m.group(1) + " " + first + (second == null ? "" : "," + second) + ": "
                        + structuralText(m.group(4), SEQUENCE_SEPARATOR));
            } else if ((m = match("^(activate|deactivate) (" + ID + ")$", line)) != null) {
                String id = identifier(m.group(2), "sequenceDiagram");
                activation(id, m.group(1).equals("activate") ? 1 : -1, references, active);
                out.add(m.group(1) + " " + id);
            } else if ((m = match("^(" + ID + ")(-->>|->>|-->|->|--\\)|-\\)|--x|-x)([+-]?)(" + ID + "): (.+)$", line))
                    != null) {
                String from = identifier(m.group(1), "sequenceDiagram");
                String to = identifier(m.group(4), "sequenceDiagram");
                participants.add(from);
                participants.add(to);
                if (m.group(3).equals("+")) activation(to, 1, references, active);
                if (m.group(3).equals("-")) activation(from, -1, references, active);
                out.add(from + m.group(2) + m.group(3) + to + ": " + structuralText(m.group(5), SEQUENCE_SEPARATOR));
            } else
                fail(
                        "unsupported sequence statement; allowed: participant, actor, message, note, activate/deactivate, autonumber, alt/loop/opt/par, title");
        }
        if (!stack.isEmpty()) fail("unclosed sequence block");
        for (String id : references)
            if (!participants.contains(id))
                fail("sequence.activation-participant", "activation references unknown participant");
        return out;
    }

    private static void activation(String id, int delta, Set<String> references, Map<String, Integer> active) {
        references.add(id);
        int depth = active.getOrDefault(id, 0) + delta;
        if (depth < 0) fail("sequence.activation-balance", "deactivation of inactive participant");
        active.put(id, depth);
    }

    private String erName(Scanner s) {
        s.space();
        if (s.pos < s.line.length() && s.line.charAt(s.pos) == '"') return quoted(s.quoted());
        return identifier(s.id(), "erDiagram");
    }

    private List<String> state(List<String> lines, String header) {
        List<String> out = new ArrayList<>(List.of(header));
        Set<String> states = new HashSet<>();
        String noteId = null;
        String noteSide = null;
        List<String> noteParts = new ArrayList<>();
        for (String line : lines) {
            advance(line);
            Matcher m;
            if (noteId != null) {
                if (line.equals("end note")) {
                    if (noteParts.isEmpty()) fail("empty state note");
                    out.add("note " + noteSide + " of " + noteId + " : " + String.join("<br/>", noteParts));
                    noteId = null;
                    noteSide = null;
                    noteParts.clear();
                } else noteParts.add(structuralText(line, STATE_SEPARATOR));
            } else if ((m = match("^note (left|right) of (" + ID + ")$", line)) != null) {
                noteId = identifier(m.group(2), "stateDiagram-v2");
                if (!states.contains(noteId)) fail("state.note-after-state", "note references unknown state");
                noteSide = m.group(1);
            } else if ((m = match("^state \"([^\"]+)\" as (" + ID + ")$", line)) != null) {
                String id = identifier(m.group(2), "stateDiagram-v2");
                states.add(id);
                out.add("state " + quoted(m.group(1)) + " as " + id);
            } else if ((m = match("^(\\[\\*\\]|" + ID + ")\\s*-->\\s*(\\[\\*\\]|" + ID + ")(?:\\s*:\\s*(.+))?$", line))
                    != null) {
                for (String id : List.of(m.group(1), m.group(2)))
                    if (!id.equals("[*]")) states.add(identifier(id, "stateDiagram-v2"));
                out.add(m.group(1) + " --> " + m.group(2)
                        + (m.group(3) != null ? " : " + structuralText(m.group(3), STATE_SEPARATOR) : ""));
            } else if ((m = match("^note (left|right) of (" + ID + ") : (.+)$", line)) != null) {
                String id = identifier(m.group(2), "stateDiagram-v2");
                if (!states.contains(id)) fail("state.note-after-state", "note references unknown state");
                out.add("note " + m.group(1) + " of " + id + " : " + structuralText(m.group(3), STATE_SEPARATOR));
            } else fail("unsupported state statement; allowed: state alias, ID --> ID[: label], note left/right of ID");
        }
        if (noteId != null) fail("unclosed state note");
        return out;
    }
}
