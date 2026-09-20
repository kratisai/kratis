package com.kratisai.controlplane.service.command;

import com.kratisai.controlplane.api.wsdto.CommandSegment;
import com.kratisai.controlplane.model.HitlRuleType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a shell command into its root commands at top-level operators. Fail-closed: hidden-command
 * constructs mark the whole parse untrusted, and segments with assignments, expansions, or
 * redirections are never auto-allowable.
 */
public final class ShellCommandSplitter {

    public record ParsedSegment(String text, String suggestedRoot, boolean autoAllowable) {}

    public record ParseResult(String command, List<ParsedSegment> segments, boolean fullyParsed) {
        public ParseResult {
            segments = List.copyOf(segments);
        }

        public List<CommandSegment> toWireSegments() {
            return segments.stream()
                    .map(segment ->
                            new CommandSegment(segment.text(), segment.suggestedRoot(), HitlRuleType.PREFIX_WILD))
                    .toList();
        }
    }

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=");
    private static final Pattern SUBCOMMAND_WORD = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_:+@-]*$");
    private static final Pattern REDIRECT_WORD = Pattern.compile("^\\d*(<<|>>|<|>)");
    private static final Pattern BARE_REDIRECT_WORD = Pattern.compile("^\\d*(<<|>>|<|>)$");

    private ShellCommandSplitter() {}

    public static ParseResult parse(String command) {
        if (command == null || command.isBlank()) {
            return new ParseResult(command == null ? "" : command, List.of(), true);
        }
        Scan scan = scan(command);
        List<ParsedSegment> segments = new ArrayList<>();
        for (String raw : scan.rawSegments) {
            String text = raw.trim();
            if (text.isEmpty()) {
                continue;
            }
            segments.add(analyzeSegment(text, scan.fullyParsed));
        }
        return new ParseResult(command, segments, scan.fullyParsed);
    }

    private static Scan scan(String command) {
        Scan scan = new Scan();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            if (inSingle) {
                current.append(c);
                inSingle = c != '\'';
                i++;
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < n) {
                    current.append(c).append(command.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inDouble = false;
                    current.append(c);
                    i++;
                    continue;
                }
                if (isSubstitutionStart(command, i)) {
                    scan.fullyParsed = false;
                }
                current.append(c);
                i++;
                continue;
            }
            switch (c) {
                case '\'' -> {
                    inSingle = true;
                    current.append(c);
                    i++;
                }
                case '"' -> {
                    inDouble = true;
                    current.append(c);
                    i++;
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        current.append(c).append(command.charAt(i + 1));
                        i += 2;
                    } else {
                        scan.fullyParsed = false;
                        current.append(c);
                        i++;
                    }
                }
                case '&', '|', ';', '\n', '\r' -> {
                    if (c == '&' && isFdDuplication(current, command, i)) {
                        current.append(c);
                        i++;
                    } else {
                        scan.closeSegment(current);
                        i += operatorLength(command, i);
                    }
                }
                default -> {
                    if (isSubstitutionStart(command, i) || c == '(' || c == ')' || isHeredoc(command, i)) {
                        scan.fullyParsed = false;
                    }
                    current.append(c);
                    i++;
                }
            }
        }
        if (inSingle || inDouble) {
            scan.fullyParsed = false;
        }
        scan.closeSegment(current);
        return scan;
    }

    private static final class Scan {
        private final List<String> rawSegments = new ArrayList<>();
        private boolean fullyParsed = true;

        void closeSegment(StringBuilder current) {
            rawSegments.add(current.toString());
            current.setLength(0);
        }
    }

    private static boolean isFdDuplication(StringBuilder current, String command, int i) {
        if (i + 1 < command.length()) {
            char next = command.charAt(i + 1);
            if (next == '&') {
                return false;
            }
            if (next == '>') {
                return true;
            }
        }
        int end = current.length() - 1;
        while (end >= 0 && Character.isDigit(current.charAt(end))) {
            end--;
        }
        return end >= 0 && (current.charAt(end) == '>' || current.charAt(end) == '<');
    }

    private static int operatorLength(String command, int i) {
        if (i + 1 < command.length()) {
            char next = command.charAt(i + 1);
            char c = command.charAt(i);
            if ((c == '&' && next == '&') || (c == '|' && (next == '|' || next == '&'))) {
                return 2;
            }
        }
        return 1;
    }

    private static boolean isSubstitutionStart(String command, int i) {
        char c = command.charAt(i);
        if (c == '`') {
            return true;
        }
        if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
            return true;
        }
        if ((c == '<' || c == '>') && i + 1 < command.length() && command.charAt(i + 1) == '(') {
            return true;
        }
        return false;
    }

    private static boolean isHeredoc(String command, int i) {
        return command.charAt(i) == '<' && i + 1 < command.length() && command.charAt(i + 1) == '<';
    }

    private static ParsedSegment analyzeSegment(String text, boolean fullyParsed) {
        List<String> words = tokenize(text);
        boolean autoAllowable = fullyParsed;
        List<String> rootTokens = new ArrayList<>();
        int index = 0;
        while (index < words.size()) {
            String word = words.get(index);
            if (REDIRECT_WORD.matcher(word).find()) {
                autoAllowable = false;
                if (BARE_REDIRECT_WORD.matcher(word).matches() && index + 1 < words.size()) {
                    index++;
                }
                index++;
                continue;
            }
            if (rootTokens.isEmpty() && ENV_ASSIGNMENT.matcher(word).find()) {
                autoAllowable = false;
                index++;
                continue;
            }
            break;
        }
        if (index >= words.size()) {
            return new ParsedSegment(text, "", false);
        }
        String program = words.get(index);
        if (containsExpansion(program)) {
            autoAllowable = false;
        }
        rootTokens.add(program);
        // Extending a path-like program would bake one-off arguments into the remembered rule.
        boolean extendRoot = !program.contains("/");
        boolean rootDone = false;
        for (int j = index + 1; j < words.size(); j++) {
            String word = words.get(j);
            // Flag detection continues after root building stops.
            if (REDIRECT_WORD.matcher(word).find() || containsExpansion(word)) {
                autoAllowable = false;
            }
            if (!extendRoot || rootDone || rootReachedArgument(word)) {
                rootDone = true;
                continue;
            }
            rootTokens.add(word);
        }
        String suggestedRoot = String.join(" ", rootTokens);
        if (suggestedRoot.isBlank()) {
            autoAllowable = false;
        }
        return new ParsedSegment(text, suggestedRoot, autoAllowable);
    }

    private static boolean rootReachedArgument(String word) {
        return !SUBCOMMAND_WORD.matcher(word).matches();
    }

    private static boolean containsExpansion(String word) {
        boolean inSingle = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (inSingle) {
                continue;
            }
            if (c == '\\' && i + 1 < word.length()) {
                i++;
                continue;
            }
            if (c == '$' || c == '`') {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenize(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingle) {
                word.append(c);
                inSingle = c != '\'';
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < text.length()) {
                    word.append(c).append(text.charAt(i + 1));
                    i++;
                    continue;
                }
                word.append(c);
                inDouble = c != '"';
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                word.append(c);
                continue;
            }
            if (c == '"') {
                inDouble = true;
                word.append(c);
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                word.append(c).append(text.charAt(i + 1));
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
                continue;
            }
            word.append(c);
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }
}
