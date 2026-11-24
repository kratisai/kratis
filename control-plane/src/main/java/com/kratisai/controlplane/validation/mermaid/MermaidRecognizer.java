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
    private static final String CLASS_ID = ID + "(?:\\." + ID + ")*";
    private static final Pattern CLASS_ID_PREFIX = Pattern.compile(CLASS_ID);
    private static final List<String> CLASS_ARROWS = List.of(
            "<|--", "*--", "o--", "-->", "..>", "..|>", "<|..", "<..", "o..", "..o", "--|>", "--*", "--o", "<--", "--");
    private static final List<String> SEQUENCE_ARROWS = List.of("-->>", "->>", "-->", "->", "--)", "-)", "--x", "-x");
    private static final String NUM = "-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)";
    private static final Pattern ENTITY = Pattern.compile("#quot;|#(\\d+);");
    private static final Pattern BREAK = Pattern.compile("<br[ \\t]*/?>|\\\\n", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY = Pattern.compile("&[#A-Za-z0-9]");
    private static final Pattern RAW_FORBIDDEN =
            Pattern.compile("[\"<>\\r\\n\\x00-\\x1f]|#(?:quot|\\d+);|&[#A-Za-z0-9]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOW_HEADER = Pattern.compile("^(flowchart|graph)(?i:(?: (?:td|tb|bt|lr|rl)))?$");
    private static final Pattern FLOW_HEADER_DIRECTION = Pattern.compile("^(?:flowchart|graph)(?: ([a-zA-Z]{2}))?$");
    private static final Pattern FLOW_AMBIGUOUS = Pattern.compile("[\"\\[\\]{}|]");
    private static final Pattern INLINE_SOLID = Pattern.compile("^--[ \\t]*\"([^\"]*)\"[ \\t]*(-->|---|--o|--x)");
    private static final Pattern INLINE_DOTTED = Pattern.compile("^-\\.[ \\t]*\"([^\"]*)\"[ \\t]*(\\.-?>|-?\\.-)");
    private static final Pattern INLINE_THICK = Pattern.compile("^==[ \\t]*\"([^\"]*)\"[ \\t]*(==>|===|===o|===x)");
    private static final Pattern INLINE_QUOTED_EDGE = Pattern.compile("^(?:--|-\\.|==)[ \\t]*\"");
    private static final Pattern IGNORED_STYLE = Pattern.compile("^style [A-Za-z0-9_.]+ .+$");
    private static final Pattern IGNORED_CLASS_DEF = Pattern.compile("^classDef [A-Za-z0-9_]+ .+$");
    private static final Pattern CARDINALITY = Pattern.compile("^(?:\\|\\||\\|o|o\\||}o|o\\{|}\\||\\|\\{)");
    private static final Pattern KEY = Pattern.compile("^(?:PK|FK|UK)(?:,[ \\t]*(?:PK|FK|UK))*");
    private static final Pattern ER_TYPE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(?:\\([0-9]+(?:,[0-9]+)?\\))?(?:\\[\\])*");
    private static final Pattern C4_NAME = Pattern.compile("^[A-Za-z_]+");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^" + NUM);
    private static final Pattern SEQUENCE_SEPARATOR = Pattern.compile(
            ";[ \\t]*(?:[A-Za-z][A-Za-z0-9_]*[ \\t]*(?:-->>|->>|-->|->|--\\)|-\\)|--x|-x|<<--|<<-|<--|<-)|(?:participant|actor|create|destroy|alt|loop|opt|par|par_over|else|and|rect|box|critical|option|break|end|note|activate|deactivate|title|links|link|properties|details|autonumber)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_SEPARATOR = Pattern.compile(
            ";[ \\t]*(?:(?:class|classDef|namespace|style|link|click|note|direction)\\b|[A-Za-z][A-Za-z0-9_]*[^;\\n]*?(?:<\\|--|\\*--|o--|-->|\\.\\.>|\\.\\.\\|>|<\\|\\.\\.|<\\.\\.|o\\.\\.|\\.\\.o|--\\|>|--\\*|--o|<--|--))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATE_SEPARATOR = Pattern.compile(
            ";[ \\t]*(?:(?:\\[\\*\\]|[A-Za-z][A-Za-z0-9_]*)[ \\t]*-->|(?:state|note|class|classDef|direction|end)(?:[ \\t]|$))");
    private static final Pattern JOURNEY_SEPARATOR =
            Pattern.compile(";[ \\t]*(?:(?:title|section)[ \\t]|[^:;]+:[ \\t]*-?\\d+[ \\t]*:)");
    private static final Pattern ENTITY_PREFIX = Pattern.compile("#(?:quot|\\d+)$");
    static final Map<String, int[]> C4_ELEMENTS = Map.ofEntries(
            Map.entry("Boundary", new int[] {2, 3}),
            Map.entry("Component", new int[] {2, 4}),
            Map.entry("ComponentDb", new int[] {2, 4}),
            Map.entry("ComponentQueue", new int[] {2, 4}),
            Map.entry("Component_Ext", new int[] {2, 4}),
            Map.entry("Container", new int[] {2, 4}),
            Map.entry("ContainerDb", new int[] {2, 4}),
            Map.entry("ContainerQueue", new int[] {2, 4}),
            Map.entry("Container_Boundary", new int[] {2, 2}),
            Map.entry("Container_Ext", new int[] {2, 4}),
            Map.entry("Person", new int[] {2, 3}),
            Map.entry("Person_Ext", new int[] {2, 3}),
            Map.entry("Rel", new int[] {3, 4}),
            Map.entry("Rel_Neighbor", new int[] {3, 4}),
            Map.entry("System", new int[] {2, 3}),
            Map.entry("SystemDb", new int[] {2, 4}),
            Map.entry("SystemQueue", new int[] {2, 4}),
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
            for (String line : lines) {
                if (line.startsWith("%%{")) {
                    advance(line);
                    fail("init directives are unsupported");
                }
            }
            lines.removeIf(MermaidRecognizer::ignoredLine);
            String type;
            if (FLOW_HEADER.matcher(header).matches()) {
                type = "flowchart";
            } else if (header.equals("stateDiagram")) {
                type = "stateDiagram-v2";
            } else if (header.equals("pie") || header.startsWith("pie title ") || header.equals("pie title")) {
                type = "pie";
            } else if (header.equals("journey")
                    || header.startsWith("journey title ")
                    || header.equals("journey title")) {
                type = "journey";
            } else {
                type = header;
            }
            if (!grammar.headers.containsKey(type)
                    || (!type.equals("flowchart")
                            && !type.equals("pie")
                            && !type.equals("journey")
                            && !grammar.headers.get(type).contains(header)))
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
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmedWhitespace(value.charAt(start))) start++;
        while (end > start && isTrimmedWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isTrimmedWhitespace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character) || character == '\ufeff';
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
            fail("identifier.reserved", "reserved identifier `" + value + "`; choose a non-keyword identifier");
        return value;
    }

    private String classIdentifier(String value) {
        for (String part : value.split("\\.", -1)) {
            if (!grammar.identifier.matcher(part).matches()) fail("invalid class identifier");
            if (grammar.reserved.get("classDiagram").contains(part.toLowerCase(Locale.ROOT)))
                fail(
                        "identifier.reserved",
                        "reserved identifier `" + part + "`; choose a non-keyword class identifier");
        }
        return value;
    }

    private String flowIdentifier(String value) {
        if (!grammar.flowIdentifier.matcher(value).matches()) fail("invalid flowchart identifier");
        if (grammar.reserved.get("flowchart").contains(value) || value.equals("classDef") || value.equals("linkStyle"))
            fail("identifier.reserved", "reserved identifier `" + value + "`; choose a non-keyword identifier");
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
        List<String> out = new ArrayList<>(List.of(flowHeader(header)));
        List<String> stack = new ArrayList<>();
        List<Set<String>> scopedEndpoints = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        Set<String> nodes = new HashSet<>();
        int completedGroups = 0;
        for (String line : lines) {
            advance(line);
            if (line.startsWith("direction ")) {
                out.add("direction " + flowDirection(line.substring(10)));
                continue;
            }
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
                String[] firstParts = first.split(" & ");
                List<String> firstIds = new ArrayList<>();
                for (String part : firstParts) {
                    String firstId = flowPrefixId(part);
                    firstIds.add(firstId);
                    if (!part.equals(firstId)) nodes.add(firstId);
                }
                s.space();
                boolean standalone = s.pos == line.length();
                List<String> endpoints = new ArrayList<>(firstIds);
                while (s.pos < line.length()) {
                    String arrow = null;
                    String label;
                    String remaining = s.line.substring(s.pos);
                    Matcher inlineMatcher;
                    if ((inlineMatcher = INLINE_SOLID.matcher(remaining)).lookingAt()) {
                        arrow = inlineMatcher.group(2);
                        label = "|" + quoted(text(inlineMatcher.group(1))) + "|";
                        s.pos += inlineMatcher.end();
                    } else if ((inlineMatcher = INLINE_DOTTED.matcher(remaining)).lookingAt()) {
                        String close = inlineMatcher.group(2);
                        arrow = close.contains(">") ? "-.->" : "-.-";
                        label = "|" + quoted(text(inlineMatcher.group(1))) + "|";
                        s.pos += inlineMatcher.end();
                    } else if ((inlineMatcher = INLINE_THICK.matcher(remaining)).lookingAt()) {
                        arrow = inlineMatcher.group(2);
                        label = "|" + quoted(text(inlineMatcher.group(1))) + "|";
                        s.pos += inlineMatcher.end();
                    } else {
                        checkBrokenInlineEdge(remaining);
                        for (String candidate : grammar.flowArrows) {
                            if (s.line.startsWith(candidate, s.pos)) {
                                arrow = candidate;
                                break;
                            }
                        }
                        if (arrow == null) {
                            if (remaining.startsWith("--")
                                    || remaining.startsWith("-.")
                                    || remaining.startsWith("==")) {
                                fail("unquoted inline edge label; quote it, e.g. -- \"label\" -->");
                            }
                            fail("expected an arrow such as -->, e.g. A --> B");
                        }
                        s.pos += arrow.length();
                        label = s.maybe("|") ? "|" + flowLabel(s, List.of("|")).label() + "|" : "";
                    }
                    String to = flowNode(s);
                    String[] toParts = to.split(" & ");
                    for (String part : toParts) {
                        String toId = flowPrefixId(part);
                        endpoints.add(toId);
                        if (!part.equals(toId)) nodes.add(toId);
                    }
                    result.append(' ').append(arrow).append(label).append(' ').append(to);
                    s.space();
                }
                if (standalone) nodes.addAll(firstIds);
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

    private static void checkBrokenInlineEdge(String remaining) {
        if (INLINE_QUOTED_EDGE.matcher(remaining).lookingAt()) {
            int firstQuote = remaining.indexOf('"');
            int secondQuote = remaining.indexOf('"', firstQuote + 1);
            if (secondQuote < 0) {
                fail("unterminated quoted label in edge; e.g. -- \"label\" -->");
            } else {
                fail("missing or invalid closing arrow for inline edge label; e.g. -- \"label\" -->");
            }
        }
    }

    private static String flowHeader(String header) {
        Matcher matcher = FLOW_HEADER_DIRECTION.matcher(header);
        if (!matcher.matches() || matcher.group(1) == null) return "flowchart";
        return "flowchart " + matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private static boolean ignoredLine(String line) {
        return line.startsWith("%%")
                || IGNORED_STYLE.matcher(line).matches()
                || IGNORED_CLASS_DEF.matcher(line).matches()
                || line.startsWith("linkStyle ");
    }

    private static String flowDirection(String value) {
        String direction = trim(value).toUpperCase(Locale.ROOT);
        if (!Set.of("TD", "TB", "BT", "LR", "RL").contains(direction))
            fail("invalid direction; expected TD, TB, BT, LR or RL");
        return direction;
    }

    private static void addGroup(String id, Set<String> groups, Set<String> nodes) {
        if (groups.contains(id) || nodes.contains(id))
            fail("flow.subgraph-id-unique", "duplicate or conflicting subgraph id");
        groups.add(id);
    }

    private List<String> pie(List<String> lines, String header) {
        boolean strippedTitle = false;
        if (header.matches("^pie[ \\t]+title(?:[ \\t]+.*)?$")) {
            String titlePart = header.substring(9).trim();
            lines.addFirst("title " + titlePart);
            header = "pie";
            strippedTitle = true;
        }
        List<String> out = new ArrayList<>(List.of(header));
        int slices = 0;
        boolean title = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!strippedTitle || i > 0) {
                advance(line);
            }
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
            } else if ((m = match("^\"([^\"]+)\": \\[(" + NUM + "),[ \\t]*(" + NUM + ")\\]$", line)) != null) {
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
        boolean strippedTitle = false;
        if (header.matches("^journey[ \\t]+title(?:[ \\t]+.*)?$")) {
            String titlePart = header.substring(13).trim();
            lines.addFirst("title " + titlePart);
            header = "journey";
            strippedTitle = true;
        }
        List<String> out = new ArrayList<>(List.of(header));
        boolean title = false;
        boolean section = false;
        int tasks = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!strippedTitle || i > 0) {
                advance(line);
            }
            Matcher m;
            JourneyTask task;
            if ((m = match("^title (.+)$", line)) != null) {
                if (title || section) fail("duplicate or misplaced journey title");
                title = true;
                out.add("title " + structuralText(m.group(1), JOURNEY_SEPARATOR));
            } else if ((m = match("^section (.+)$", line)) != null) {
                section = true;
                out.add("section " + structuralText(m.group(1), JOURNEY_SEPARATOR));
            } else if ((task = journeyTask(line)) != null) {
                if (!section) fail("journey task requires section");
                double score = Double.parseDouble(task.score());
                if (!Double.isFinite(score)
                        || Math.abs(score) > 9007199254740991d
                        || score < grammar.journeyScoreMin
                        || score > grammar.journeyScoreMax) fail("journey.score", "journey score out of bounds");
                List<String> roles = new ArrayList<>();
                if (task.roles() != null && !trim(task.roles()).isEmpty()) {
                    for (String role : splitRoles(task.roles())) {
                        if (decode(bareText(role)).contains(",")) fail("comma inside journey role is unsupported");
                        roles.add(structuralText(role, JOURNEY_SEPARATOR));
                    }
                }
                tasks++;
                out.add(structuralText(task.text(), JOURNEY_SEPARATOR) + ": " + (int) score
                        + (roles.isEmpty() ? "" : ": " + String.join(", ", roles)));
            } else fail("unsupported journey statement; allowed: title, section, task with score and roles");
        }
        if (tasks == 0) fail("journey requires a task");
        return out;
    }

    private record JourneyTask(String text, String score, String roles) {}

    private static JourneyTask journeyTask(String line) {
        int colon = firstUnquotedColon(line);
        if (colon < 0) return null;
        String text = line.substring(0, colon);
        String rest = line.substring(colon + 1);
        int start = skipAsciiSpaces(rest, 0);
        int pos = start;
        if (pos < rest.length() && rest.charAt(pos) == '-') pos++;
        int digits = pos;
        while (pos < rest.length() && rest.charAt(pos) >= '0' && rest.charAt(pos) <= '9') pos++;
        if (pos == digits) return null;
        String score = rest.substring(start, pos);
        pos = skipAsciiSpaces(rest, pos);
        if (pos == rest.length()) return new JourneyTask(text, score, null);
        if (rest.charAt(pos) != ':') return null;
        return new JourneyTask(text, score, trim(rest.substring(pos + 1)));
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

    private String flowSingleNode(Scanner s) {
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

    private String flowNode(Scanner s) {
        String first = flowSingleNode(s);
        s.space();
        if (!s.maybe("&")) {
            return first;
        }
        List<String> parts = new ArrayList<>(List.of(first));
        do {
            parts.add(flowSingleNode(s));
            s.space();
        } while (s.maybe("&"));
        return String.join(" & ", parts);
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
                fail(
                        "unsupported C4 element; allowed: Person, System, Container, Component, ContainerDb, SystemDb, ContainerQueue, SystemQueue, ComponentDb, ComponentQueue, boundaries, Rel");
            s.literal("(");
            List<String> args = new ArrayList<>(List.of(s.id()));
            boolean relation = name.startsWith("Rel");
            if (relation) {
                s.literal(",");
                args.add(s.id());
            }
            while (s.maybe(",")) args.add(raw(s.quoted()));
            if (!s.maybe(")")) {
                s.space();
                if (s.pos < s.line.length() && s.line.charAt(s.pos) == '"') {
                    fail(
                            "C4 arguments must be comma-separated, e.g. Person(alias, \"Label\", \"Description\") and Rel(fromAlias, toAlias, \"Label\")");
                }
                fail("expected )");
            }
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
        String member;
        String relation;
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
            } else if (line.startsWith("direction ")) {
                out.add("direction " + flowDirection(line.substring(10)));
            } else if ((m = match(
                            "^class (" + CLASS_ID
                                    + ")(~[^~ \\t][^~]*~)?(?:\\[\"([^\"]+)\"\\])?(<<[A-Za-z][A-Za-z0-9_-]*>>)?( \\{)?$",
                            line))
                    != null) {
                body = m.group(5) != null;
                out.add("class " + classIdentifier(m.group(1))
                        + (m.group(2) != null ? m.group(2) : "")
                        + (m.group(3) != null ? "[" + quoted(m.group(3)) + "]" : "")
                        + (m.group(4) != null ? m.group(4) : "")
                        + (body ? " {" : ""));
            } else if ((m = match("^note for (" + CLASS_ID + ") (.+)$", line)) != null) {
                String raw = m.group(2);
                String note = raw.startsWith("\"") ? bareText(raw) : raw;
                out.add("note for " + classIdentifier(m.group(1)) + " " + quoted(note));
            } else if ((member = classMemberStatement(line)) != null) {
                out.add(member);
            } else if ((relation = classRelationStatement(line)) != null) {
                out.add(relation);
            } else
                fail(
                        "unsupported class statement; allowed: class ID, class ID { members }, ID : member, relation ID <arrow> ID [: label], note for ID");
        }
        if (body) fail("unclosed class body");
        return out;
    }

    private String classMemberStatement(String line) {
        int idEnd = classIdEnd(line, 0);
        if (idEnd < 0) return null;
        int pos = skipAsciiSpaces(line, idEnd);
        if (pos >= line.length() || line.charAt(pos) != ':') return null;
        String id = line.substring(0, idEnd);
        pos = skipAsciiSpaces(line, pos + 1);
        return classIdentifier(id) + " : " + classMember(line.substring(pos));
    }

    private String classRelationStatement(String line) {
        int leftEnd = classIdEnd(line, 0);
        if (leftEnd < 0) return null;
        int pos = skipAsciiSpaces(line, leftEnd);
        if (pos == leftEnd) return null;
        String leftId = line.substring(0, leftEnd);
        String leftCard = null;
        if (pos < line.length() && line.charAt(pos) == '"') {
            int close = line.indexOf('"', pos + 1);
            if (close < 0) return null;
            leftCard = line.substring(pos + 1, close);
            pos = skipAsciiSpaces(line, close + 1);
            if (pos == close + 1) return null;
        }
        String arrow = null;
        for (String candidate : CLASS_ARROWS)
            if (line.startsWith(candidate, pos)) {
                arrow = candidate;
                break;
            }
        if (arrow == null) return null;
        pos += arrow.length();
        if (pos >= line.length() || !isAsciiSpace(line.charAt(pos))) return null;
        pos = skipAsciiSpaces(line, pos);
        String rightCard = null;
        if (pos < line.length() && line.charAt(pos) == '"') {
            int close = line.indexOf('"', pos + 1);
            if (close < 0) return null;
            rightCard = line.substring(pos + 1, close);
            pos = skipAsciiSpaces(line, close + 1);
            if (pos == close + 1) return null;
        }
        int rightEnd = classIdEnd(line, pos);
        if (rightEnd < 0) return null;
        String rightId = line.substring(pos, rightEnd);
        int colon = skipAsciiSpaces(line, rightEnd);
        String label = null;
        if (colon < line.length() && line.charAt(colon) == ':') {
            label = line.substring(skipAsciiSpaces(line, colon + 1));
            if (label.isEmpty()) return null;
        } else if (colon != line.length()) {
            return null;
        }
        return classIdentifier(leftId) + (leftCard != null ? " " + quoted(leftCard) : "")
                + " " + arrow + " " + (rightCard != null ? quoted(rightCard) + " " : "")
                + classIdentifier(rightId)
                + (label != null ? " : " + structuralText(label, CLASS_SEPARATOR) : "");
    }

    private static int classIdEnd(String line, int start) {
        Matcher matcher = CLASS_ID_PREFIX.matcher(line);
        matcher.region(start, line.length());
        return matcher.lookingAt() ? matcher.end() : -1;
    }

    private static int skipAsciiSpaces(String line, int position) {
        int pos = position;
        while (pos < line.length() && isAsciiSpace(line.charAt(pos))) pos++;
        return pos;
    }

    private static boolean isAsciiSpace(char character) {
        return character == ' ' || character == '\t';
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
                    List<String> keys =
                            Arrays.stream(key.split(",")).map(String::trim).toList();
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
            SequenceMessage message;
            if (line.equals("autonumber")) {
                out.add(line);
            } else if ((m = match("^(participant|actor) (.+)$", line)) != null) {
                String rest = trim(m.group(2));
                int as = rest.lastIndexOf(" as ");
                String name;
                String alias = null;
                if (as >= 0) {
                    name = sequenceIdentifier(trim(rest.substring(0, as)));
                    alias = structuralText(rest.substring(as + 4), SEQUENCE_SEPARATOR);
                } else {
                    name = sequenceName(rest);
                }
                if (!declarations.add(name)) fail("sequence.declaration-unique", "duplicate sequence declaration");
                participants.add(name);
                out.add(m.group(1) + " " + name + (alias != null ? " as " + alias : ""));
            } else if ((m = match("^rect (.+)$", line)) != null) {
                String argument = text(m.group(1));
                if (argument.contains(";"))
                    fail("text.statement-separator", "ambiguous multiple statements on one line");
                stack.add("rect");
                depth(stack.size());
                out.add("rect " + argument);
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
            } else if (line.startsWith("Note ") || line.startsWith("note ")) {
                String rest = line.substring(5);
                String side;
                if (rest.startsWith("over ")) {
                    side = "over";
                    rest = rest.substring(5);
                } else if (rest.startsWith("left of ")) {
                    side = "left of";
                    rest = rest.substring(8);
                } else if (rest.startsWith("right of ")) {
                    side = "right of";
                    rest = rest.substring(9);
                } else {
                    fail("invalid sequence note");
                    return out;
                }
                int colon = rest.indexOf(": ");
                if (colon < 0) fail("invalid sequence note");
                List<String> names = new ArrayList<>();
                for (String part : rest.substring(0, colon).split(",", -1)) names.add(sequenceName(part));
                if (!side.equals("over") && names.size() != 1) fail("invalid note participants");
                participants.addAll(names);
                out.add("Note " + side + " " + String.join(",", names) + ": "
                        + structuralText(rest.substring(colon + 2), SEQUENCE_SEPARATOR));
            } else if ((m = match("^(activate|deactivate) (.+)$", line)) != null) {
                String id = sequenceName(m.group(2));
                activation(id, m.group(1).equals("activate") ? 1 : -1, references, active);
                out.add(m.group(1) + " " + id);
            } else if ((message = sequenceMessage(line)) != null) {
                String from = sequenceName(message.from());
                String to = sequenceName(message.to());
                participants.add(from);
                participants.add(to);
                if (message.sign().equals("+")) activation(to, 1, references, active);
                if (message.sign().equals("-")) activation(from, -1, references, active);
                out.add(from + message.arrow() + message.sign() + to + ": "
                        + structuralText(message.message(), SEQUENCE_SEPARATOR));
            } else
                fail(
                        "unsupported sequence statement; allowed: participant, actor, message, note, activate/deactivate, autonumber, alt/loop/opt/par/rect, title");
        }
        if (!stack.isEmpty()) fail("unclosed sequence block");
        for (String id : references)
            if (!participants.contains(id))
                fail("sequence.activation-participant", "activation references unknown participant");
        return out;
    }

    private record SequenceMessage(String from, String arrow, String sign, String to, String message) {}

    private static SequenceMessage sequenceMessage(String line) {
        int best = -1;
        String arrow = null;
        for (String candidate : SEQUENCE_ARROWS) {
            int index = line.indexOf(candidate);
            if (index >= 0 && (best < 0 || index < best || (index == best && candidate.length() > arrow.length()))) {
                best = index;
                arrow = candidate;
            }
        }
        if (arrow == null) return null;
        int pos = best + arrow.length();
        String sign = "";
        if (pos < line.length() && (line.charAt(pos) == '+' || line.charAt(pos) == '-')) {
            sign = String.valueOf(line.charAt(pos));
            pos++;
        }
        int colon = line.indexOf(": ", pos);
        if (colon < 0) return null;
        String from = trim(line.substring(0, best));
        String to = trim(line.substring(pos, colon));
        if (from.isEmpty() || to.isEmpty()) return null;
        return new SequenceMessage(from, arrow, sign, to, trim(line.substring(colon + 2)));
    }

    private String sequenceName(String value) {
        String name = trim(value);
        if (name.startsWith("\"")) name = bareText(name);
        if (!name.matches("[A-Za-z0-9_]+(?: [A-Za-z0-9_]+)*"))
            fail("invalid sequence name; use words separated by spaces, e.g. participant API as \"REST API\"");
        if (!name.contains(" ") && grammar.reserved.get("sequenceDiagram").contains(name.toLowerCase(Locale.ROOT)))
            fail("identifier.reserved", "reserved identifier `" + name + "`; choose a non-keyword participant name");
        return name;
    }

    private String sequenceIdentifier(String value) {
        if (!grammar.identifier.matcher(value).matches()) fail("invalid sequence identifier");
        if (grammar.reserved.get("sequenceDiagram").contains(value.toLowerCase(Locale.ROOT)))
            fail(
                    "identifier.reserved",
                    "reserved identifier `" + value + "`; choose a non-keyword participant identifier");
        return value;
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
        List<String> out = new ArrayList<>(List.of(header.equals("stateDiagram") ? "stateDiagram-v2" : header));
        Set<String> states = new HashSet<>();
        Map<String, String> aliases = new HashMap<>();
        List<String> composites = new ArrayList<>();
        String noteId = null;
        String noteSide = null;
        List<String> noteParts = new ArrayList<>();
        for (String line : lines) {
            advance(line);
            if (noteId != null) {
                if (line.equalsIgnoreCase("end note")) {
                    if (noteParts.isEmpty()) fail("empty state note");
                    out.add("note " + noteSide + " of " + noteId + " : " + String.join("<br/>", noteParts));
                    noteId = null;
                    noteSide = null;
                    noteParts.clear();
                } else noteParts.add(structuralText(line, STATE_SEPARATOR));
            } else if (line.startsWith("direction ")) {
                out.add("direction " + flowDirection(line.substring(10)));
            } else if (line.equals("}")) {
                if (composites.isEmpty()) fail("unmatched }");
                composites.removeLast();
                out.add("}");
            } else if (line.startsWith("note ") || line.startsWith("Note ")) {
                String rest = line.substring(5);
                String side;
                if (rest.startsWith("left of ")) {
                    side = "left";
                    rest = rest.substring(8);
                } else if (rest.startsWith("right of ")) {
                    side = "right";
                    rest = rest.substring(9);
                } else {
                    fail("unsupported state note; use note left/right of ID");
                    return out;
                }
                int colon = firstUnquotedColon(rest);
                if (colon >= 0) {
                    String id = stateNoteTarget(rest.substring(0, colon), states, aliases);
                    out.add("note " + side + " of " + id + " : "
                            + structuralText(rest.substring(colon + 1), STATE_SEPARATOR));
                } else {
                    noteId = stateNoteTarget(rest, states, aliases);
                    noteSide = side;
                }
            } else if (line.startsWith("state ")
                    && (firstUnquotedColon(line.substring(6)) >= 0 || indexOfUnquoted(line, "-->") < 0)) {
                String rest = line.substring(6);
                int colon = firstUnquotedColon(rest);
                if (colon >= 0) {
                    String name = trim(rest.substring(0, colon));
                    String description = trim(rest.substring(colon + 1));
                    if (name.isEmpty() || description.isEmpty()) fail("invalid state description");
                    StateRef ref = stateReference(name, states, aliases);
                    if (ref.alias() != null) out.add(ref.alias());
                    out.add(ref.id() + " : " + structuralText(description, STATE_SEPARATOR));
                } else {
                    stateDeclaration(line, states, aliases, composites, out);
                }
            } else if (indexOfUnquoted(line, "-->") >= 0) {
                int arrow = indexOfUnquoted(line, "-->");
                String left = line.substring(0, arrow);
                String right = line.substring(arrow + 3);
                int colon = firstUnquotedColon(right);
                String to = colon >= 0 ? right.substring(0, colon) : right;
                String label = colon >= 0 ? right.substring(colon + 1) : null;
                if (trim(left).isEmpty() || trim(to).isEmpty()) fail("invalid state transition");
                StateRef fromRef = stateReference(left, states, aliases);
                StateRef toRef = stateReference(to, states, aliases);
                if (fromRef.alias() != null) out.add(fromRef.alias());
                if (toRef.alias() != null) out.add(toRef.alias());
                out.add(fromRef.id() + " --> " + toRef.id()
                        + (label != null ? " : " + structuralText(label, STATE_SEPARATOR) : ""));
            } else if (firstUnquotedColon(line) >= 0) {
                int colon = firstUnquotedColon(line);
                String name = line.substring(0, colon);
                String description = line.substring(colon + 1);
                if (trim(name).isEmpty() || trim(description).isEmpty()) fail("invalid state description");
                StateRef ref = stateReference(name, states, aliases);
                if (ref.alias() != null) out.add(ref.alias());
                out.add(ref.id() + " : " + structuralText(description, STATE_SEPARATOR));
            } else
                fail(
                        "unsupported state statement; allowed: state ID, state \"Label\" as ID, state ID { ... }, ID --> ID [: label], ID : description, direction LR, note left/right of ID");
        }
        if (noteId != null) fail("unclosed state note");
        if (!composites.isEmpty()) fail("unclosed state block");
        return out;
    }

    private void stateDeclaration(
            String line, Set<String> states, Map<String, String> aliases, List<String> composites, List<String> out) {
        String rest = trim(line.substring(6));
        boolean open = rest.endsWith("{");
        if (open) rest = trim(rest.substring(0, rest.length() - 1));
        int as = rest.lastIndexOf(" as ");
        String display = as >= 0 ? trim(rest.substring(0, as)) : rest;
        String explicitId = as >= 0 ? stateIdentifier(trim(rest.substring(as + 4))) : null;
        if (display.isEmpty()) fail("state declaration requires a name");
        String id;
        String render;
        if (explicitId != null) {
            id = explicitId;
            String displayText = display.startsWith("\"") ? bareText(display) : display;
            states.add(id);
            aliases.put(displayText, id);
            render = "state " + quoted(displayText) + " as " + id;
        } else if (display.startsWith("\"")) {
            String displayText = bareText(display);
            StateRef ref = stateDisplayReference(displayText, states, aliases);
            id = ref.id();
            render = ref.alias() != null ? ref.alias() : "state " + quoted(displayText) + " as " + id;
        } else if (display.matches("[A-Za-z0-9_]+")) {
            id = stateIdentifier(display);
            states.add(id);
            render = "state " + id;
        } else {
            if (display.contains("\"") || display.startsWith("state "))
                fail("invalid state display; use a bare state name or a quoted display alias");
            StateRef ref = stateDisplayReference(display, states, aliases);
            id = ref.id();
            render = ref.alias() != null ? ref.alias() : "state " + quoted(display) + " as " + id;
        }
        out.add(render + (open ? " {" : ""));
        if (open) {
            composites.add(id);
            depth(composites.size());
        }
    }

    private StateRef stateReference(String raw, Set<String> states, Map<String, String> aliases) {
        String value = trim(raw);
        if (value.equals("[*]")) return new StateRef(value, null);
        if (value.contains("\"") || value.startsWith("state "))
            fail("invalid state reference; use an alias declaration and a bare state name, not quotes or `state ...`");
        if (value.matches("[A-Za-z0-9_]+")) {
            if (stateReserved(value))
                fail(
                        "identifier.reserved",
                        "reserved identifier `" + value + "`; choose a non-keyword state identifier");
            states.add(value);
            return new StateRef(value, null);
        }
        return stateDisplayReference(value, states, aliases);
    }

    private StateRef stateDisplayReference(String display, Set<String> states, Map<String, String> aliases) {
        String existing = aliases.get(display);
        if (existing != null) {
            states.add(existing);
            return new StateRef(existing, null);
        }
        String id = stateDisplayId(display, states);
        aliases.put(display, id);
        states.add(id);
        return new StateRef(id, "state " + quoted(display) + " as " + id);
    }

    private String stateNoteTarget(String raw, Set<String> states, Map<String, String> aliases) {
        String value = trim(raw);
        String id;
        if (value.startsWith("\"")) {
            id = aliases.get(bareText(value));
        } else if (value.matches("[A-Za-z0-9_]+")) {
            if (stateReserved(value))
                fail(
                        "identifier.reserved",
                        "reserved identifier `" + value + "`; choose a non-keyword state identifier");
            id = value;
        } else {
            id = aliases.get(value);
        }
        if (id == null || !states.contains(id)) fail("state.note-after-state", "note references unknown state");
        return id;
    }

    private String stateIdentifier(String value) {
        if (!value.matches("[A-Za-z0-9_]+")) fail("invalid state identifier");
        if (stateReserved(value))
            fail("identifier.reserved", "reserved identifier `" + value + "`; choose a non-keyword state identifier");
        return value;
    }

    private boolean stateReserved(String value) {
        return grammar.reserved.get("stateDiagram-v2").contains(value.toLowerCase(Locale.ROOT));
    }

    private static int indexOfUnquoted(String value, String token) {
        boolean quoted = false;
        for (int i = 0; i <= value.length() - token.length(); i++) {
            char character = value.charAt(i);
            if (character == '"') quoted = !quoted;
            else if (!quoted && value.startsWith(token, i)) return i;
        }
        return -1;
    }

    private static int firstUnquotedColon(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"') quoted = !quoted;
            else if (character == ':' && !quoted) return i;
        }
        return -1;
    }

    private String stateDisplayId(String display, Set<String> states) {
        String base = display.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "State";
        if (!Character.isLetter(base.charAt(0))) base = "State_" + base;
        String candidate = base;
        int suffix = 2;
        while (states.contains(candidate) || stateReserved(candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }

    private record StateRef(String id, String alias) {}
}
