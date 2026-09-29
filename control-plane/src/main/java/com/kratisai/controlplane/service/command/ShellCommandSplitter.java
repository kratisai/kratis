package com.kratisai.controlplane.service.command;

import com.kratisai.controlplane.model.CommandText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShellCommandSplitter: Lightweight, self-contained shell command splitter.
 *
 * <p>Decomposes shell commands into independent, approvable capability segments:
 * <ol>
 *   <li><b>Environment Assignments</b>: e.g. {@code NODE_ENV=test} &rarr; root {@code NODE_ENV=*}</li>
 *   <li><b>Command Execution</b>: e.g. {@code npm test -- legacy} &rarr; root {@code npm test -- legacy}</li>
 *   <li><b>Filesystem Redirections</b>: e.g. {@code > /tmp/out.log 2>&1} &rarr; root {@code > /tmp/* 2>&1}</li>
 * </ol>
 */
public final class ShellCommandSplitter {

    public record ParsedSegment(String text, String suggestedRoot) {
        public ParsedSegment {
            text = CommandText.sanitize(text);
            suggestedRoot = CommandText.sanitize(suggestedRoot);
        }
    }

    public record ParseResult(String command, List<ParsedSegment> segments, boolean fullyParsed) {
        public ParseResult {
            command = CommandText.sanitize(command);
            segments = List.copyOf(segments);
        }

        /** Distinct suggested roots, preserving first-seen order, for compact remember suggestions. */
        public List<ParsedSegment> uniqueRoots() {
            List<ParsedSegment> unique = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ParsedSegment segment : segments) {
                if (segment.suggestedRoot().isEmpty() || seen.add(segment.suggestedRoot())) {
                    unique.add(segment);
                }
            }
            return List.copyOf(unique);
        }
    }

    private static final Pattern REDIR_OP = Pattern.compile("^(\\d*(?:>>|<|>)|&>>|&>|\\d*>&\\d+|\\d*<\\&\\d+)$");
    private static final Pattern ATTACHED_REDIR = Pattern.compile("^(\\d*(?:>>|<|>)|&>>|&>)(.+)");
    private static final Pattern ENV_ASSIGN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)");
    private static final Pattern SUBCOMMAND = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_:+@-]*$");
    private static final Pattern PATH_OR_FILE = Pattern.compile(".*[/\\\\*?\\[\\]].*|.*\\.[a-zA-Z0-9]+$");

    private ShellCommandSplitter() {}

    public static ParseResult parse(String command) {
        if (command == null || command.isBlank()) {
            return new ParseResult(command == null ? "" : command, List.of(), true);
        }

        // 1. Continuations & 2. Heredocs
        String cleaned = command.replaceAll("\\\\\\r?\\n", " ");
        boolean hasHeredoc = cleaned.contains("<<");
        if (hasHeredoc) {
            cleaned = collapseHeredoc(cleaned);
        }

        // 3. Top-level operator split
        SplitScan scan = splitTopLevel(cleaned);
        boolean fullyParsed = !hasHeredoc && scan.fullyParsed();

        // 4. Tokenize & decompose
        List<ParsedSegment> segments = new ArrayList<>();

        for (String raw : scan.segments()) {
            String segText = CommandText.sanitize(raw);
            if (segText.isEmpty()) continue;

            segments.addAll(decomposeSegment(segText));
        }

        return new ParseResult(command, segments, fullyParsed);
    }

    private static List<ParsedSegment> decomposeSegment(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return List.of();

        List<ParsedSegment> results = new ArrayList<>();
        int i = 0;

        // Extract leading environment variable assignments as independent segments
        while (i < tokens.size()) {
            Matcher envMatcher = ENV_ASSIGN.matcher(tokens.get(i));
            if (envMatcher.matches()) {
                String varName = envMatcher.group(1);
                results.add(new ParsedSegment(tokens.get(i), varName + "=*"));
                i++;
            } else {
                break;
            }
        }

        // Extract wrappers like timeout N / env as independent segments
        if (i < tokens.size()) {
            String tok = tokens.get(i);
            if ("timeout".equals(tok)) {
                String text2 = tok;
                boolean hasDigit = false;
                if (i + 1 < tokens.size() && tokens.get(i + 1).matches("^\\d+[smhd]?$")) {
                    text2 = tok + " " + tokens.get(i + 1);
                    hasDigit = true;
                }
                results.add(new ParsedSegment(text2, "timeout *"));
                i += hasDigit ? 2 : 1;
            } else if ("env".equals(tok) || "/usr/bin/env".equals(tok)) {
                results.add(new ParsedSegment(tok, tok));
                i++;
                // Handle any env assignments following the env command (e.g. env FOO=bar cmd)
                while (i < tokens.size()) {
                    Matcher envMatcher = ENV_ASSIGN.matcher(tokens.get(i));
                    if (envMatcher.matches()) {
                        String varName = envMatcher.group(1);
                        results.add(new ParsedSegment(tokens.get(i), varName + "=*"));
                        i++;
                    } else {
                        break;
                    }
                }
            }
        }

        List<String> cmdTokens = new ArrayList<>();
        List<String> redirTokens = new ArrayList<>();
        List<String> genRedirTokens = new ArrayList<>();

        for (; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            if ("<<".equals(tok)) {
                cmdTokens.add("<<");
                continue;
            }
            if (REDIR_OP.matcher(tok).matches()) {
                if (tok.contains(">&") || tok.contains("<&") || i + 1 >= tokens.size()) {
                    redirTokens.add(tok);
                    genRedirTokens.add(tok);
                } else {
                    String target = tokens.get(++i);
                    redirTokens.add(tok + " " + target);
                    genRedirTokens.add(tok + " " + generalizePath(target));
                }
                continue;
            }
            Matcher m = ATTACHED_REDIR.matcher(tok);
            if (m.matches()) {
                String op = m.group(1);
                String target = m.group(2);
                redirTokens.add(op + (target.startsWith("&") ? target : " " + target));
                genRedirTokens.add(op + (target.startsWith("&") ? target : " " + generalizePath(target)));
                continue;
            }
            cmdTokens.add(tok);
        }

        if (!cmdTokens.isEmpty()) {
            results.add(analyzeCommand(cmdTokens, String.join(" ", cmdTokens)));
        }
        if (!redirTokens.isEmpty()) {
            String redirText = String.join(" ", redirTokens);
            String redirRoot = cmdTokens.isEmpty() ? "" : String.join(" ", genRedirTokens);
            results.add(new ParsedSegment(redirText, redirRoot));
        }

        return results;
    }

    private static ParsedSegment analyzeCommand(List<String> tokens, String cmdText) {
        String program = tokens.getFirst();
        List<String> rootTokens = new ArrayList<>();
        rootTokens.add(program);

        boolean seenFlag = false;
        boolean afterDoubleDash = false;
        boolean keepAfterDash = false;

        for (int i = 1; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            if ("<<".equals(tok)) {
                rootTokens.add("<<");
                continue;
            }
            if ("--".equals(tok)) {
                rootTokens.add("--");
                afterDoubleDash = true;
                keepAfterDash = true;
                continue;
            }
            if (afterDoubleDash) {
                if (keepAfterDash && !PATH_OR_FILE.matcher(tok).matches()) {
                    rootTokens.add(tok);
                    keepAfterDash = false;
                }
                continue;
            }
            if (tok.startsWith("-")) {
                seenFlag = true;
                int eq = tok.indexOf('=');
                rootTokens.add(eq > 0 ? tok.substring(0, eq) : tok);
                if (tok.equals("-m") || tok.equals("-rf") || tok.equals("-f") || tok.equals("-o")) break;
                continue;
            }
            if (!seenFlag
                    && SUBCOMMAND.matcher(tok).matches()
                    && !PATH_OR_FILE.matcher(tok).matches()) {
                if (program.endsWith(".sh")) break;
                rootTokens.add(tok);
                continue;
            }
            break;
        }

        return new ParsedSegment(cmdText, String.join(" ", rootTokens));
    }

    private static String generalizePath(String path) {
        String p = path.strip().replaceAll("^[\"']|[\"']$", "");
        if (p.startsWith("/tmp/") || p.startsWith("/tmp")) return "/tmp/*";
        if (p.startsWith("/dev/")) return "/dev/*";
        if (p.startsWith("~/") || p.startsWith("~")) return "~/*";
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(0, lastSlash + 1) + "*" : "*";
    }

    private static String collapseHeredoc(String cmd) {
        Pattern pat = Pattern.compile("<<-?\\s*(?:'([^']*)'|\"([^\"]*)\"|(\\S+))");
        Matcher m = pat.matcher(cmd);
        if (!m.find()) return cmd;
        String delim = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
        int nl = cmd.indexOf('\n', m.end());
        if (nl < 0) return cmd;

        int delimIdx = cmd.indexOf("\n" + delim, nl);
        if (delimIdx < 0) return cmd;

        int endOfDelim = delimIdx + 1 + delim.length();
        return cmd.substring(0, m.start()) + "<< " + cmd.substring(m.end(), nl) + cmd.substring(endOfDelim);
    }

    private record SplitScan(List<String> segments, boolean fullyParsed) {}

    private static SplitScan splitTopLevel(String cmd) {
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, hasSubstOrSubshell = false;
        int paren = 0, brace = 0;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);

            if (c == '\\' && !inSingle) {
                cur.append(c);
                if (i + 1 < cmd.length()) cur.append(cmd.charAt(++i));
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                cur.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                cur.append(c);
                continue;
            }

            if (!inSingle && !inDouble) {
                if (c == '`' || (c == '$' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '(')) {
                    hasSubstOrSubshell = true;
                }
                if (c == '(') {
                    paren++;
                    hasSubstOrSubshell = true;
                } else if (c == ')' && paren > 0) paren--;
                else if (c == '{') brace++;
                else if (c == '}' && brace > 0) brace--;

                if (paren == 0 && brace == 0) {
                    boolean isAnd = c == '&'
                            && (i + 1 >= cmd.length()
                                    || (cmd.charAt(i + 1) != '>' && (i == 0 || cmd.charAt(i - 1) != '>')));
                    if (isAnd || c == '|' || c == ';') {
                        segments.add(cur.toString());
                        cur.setLength(0);
                        if (i + 1 < cmd.length()
                                && ((c == '&' && cmd.charAt(i + 1) == '&')
                                        || (c == '|' && (cmd.charAt(i + 1) == '|' || cmd.charAt(i + 1) == '&')))) {
                            i++;
                        }
                        continue;
                    }
                    if (c == '\n' || c == '\r') {
                        segments.add(cur.toString());
                        cur.setLength(0);
                        continue;
                    }
                }
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) segments.add(cur.toString());
        boolean fullyParsed = !inSingle && !inDouble && paren == 0 && brace == 0 && !hasSubstOrSubshell;
        return new SplitScan(segments, fullyParsed);
    }

    private static List<String> tokenize(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                w.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                w.append(c);
                continue;
            }
            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (!w.isEmpty()) {
                    words.add(w.toString());
                    w.setLength(0);
                }
                continue;
            }
            w.append(c);
        }
        if (!w.isEmpty()) words.add(w.toString());
        return words;
    }
}
