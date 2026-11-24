package com.kratisai.controlplane.validation.mermaid;

import com.kratisai.controlplane.validation.mermaid.MermaidFenceScanner.FenceSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

@Component
public class MermaidNormalizer {

    private static final Set<String> C4_HEADERS = Set.of("C4Context", "C4Container", "C4Component");

    private final MermaidGrammar grammar = MermaidGrammar.load();
    private final MermaidRecognizer recognizer = new MermaidRecognizer(grammar);

    public String normalize(String markdownContent) {
        return fixC4Commas(normalizeFences(markdownContent));
    }

    public String normalizeFences(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) return markdownContent;
        String eol = markdownContent.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = markdownContent.split("\\r\\n|\\n|\\r", -1);
        List<String> output = new ArrayList<>(lines.length);
        boolean changed = false;
        int i = 0;
        while (i < lines.length) {
            int currentQuoteDepth = MermaidFenceScanner.quoteDepth(lines[i]);
            String line = MermaidFenceScanner.stripBlockQuote(lines[i]);
            Matcher open = MermaidFenceScanner.OPEN.matcher(line);
            if (!open.matches()) {
                output.add(lines[i]);
                i++;
                continue;
            }
            String fence = open.group(1);
            String info = open.group(2).trim();
            if (fence.charAt(0) == '`' && info.contains("`")) {
                output.add(lines[i]);
                i++;
                continue;
            }
            FenceSpan span = MermaidFenceScanner.findFenceSpan(lines, i + 1, fence, currentQuoteDepth);
            int bodyEnd = span.bodyEnd();
            String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            boolean headerAsFence = grammar.isHeaderKeyword(firstToken);
            boolean genericFence = firstToken.isEmpty() || firstToken.equals("text");

            if (headerAsFence
                    || (genericFence && MermaidFenceScanner.bodyStartsWithHeader(lines, i + 1, bodyEnd, grammar))) {
                changed = true;
                String prefix = MermaidFenceScanner.quotePrefix(lines[i]);
                String openIndent = line.substring(0, open.start(1));
                output.add(prefix + openIndent + fence + "mermaid");
                if (headerAsFence && !MermaidFenceScanner.bodyStartsWithHeader(lines, i + 1, bodyEnd, grammar)) {
                    output.add(prefix + info);
                }
            } else {
                output.add(lines[i]);
            }
            output.addAll(Arrays.asList(lines).subList(i + 1, bodyEnd));
            if (span.nextLine() > bodyEnd) {
                output.add(lines[bodyEnd]);
            }
            i = span.nextLine();
        }
        return changed ? String.join(eol, output) : markdownContent;
    }

    private String fixC4Commas(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) return markdownContent;
        String eol = markdownContent.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = markdownContent.split("\\r\\n|\\n|\\r", -1);
        List<String> output = new ArrayList<>(lines.length);
        boolean changed = false;
        int i = 0;
        while (i < lines.length) {
            int currentQuoteDepth = MermaidFenceScanner.quoteDepth(lines[i]);
            String line = MermaidFenceScanner.stripBlockQuote(lines[i]);
            Matcher open = MermaidFenceScanner.OPEN.matcher(line);
            if (!open.matches()) {
                output.add(lines[i]);
                i++;
                continue;
            }
            String fence = open.group(1);
            String info = open.group(2).trim();
            if (fence.charAt(0) == '`' && info.contains("`")) {
                output.add(lines[i]);
                i++;
                continue;
            }
            String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            FenceSpan span = MermaidFenceScanner.findFenceSpan(lines, i + 1, fence, currentQuoteDepth);
            if (!firstToken.equals("mermaid")) {
                output.addAll(Arrays.asList(lines).subList(i, span.bodyEnd()));
                if (span.nextLine() > span.bodyEnd()) output.add(lines[span.bodyEnd()]);
                i = span.nextLine();
                continue;
            }
            int bodyEnd = span.bodyEnd();
            if (span.unclosed()) {
                output.addAll(Arrays.asList(lines).subList(i, span.nextLine()));
                i = span.nextLine();
                continue;
            }
            List<String> bodyLines = new ArrayList<>(bodyEnd - i - 1);
            for (int j = i + 1; j < bodyEnd; j++) {
                bodyLines.add(MermaidFenceScanner.stripBlockQuote(lines[j]));
            }
            String body = String.join("\n", bodyLines);
            String fixedBody = fixC4Body(body);
            if (!fixedBody.equals(body)) {
                String[] fixedLines = fixedBody.split("\n", -1);
                if (fixedLines.length == bodyLines.size()
                        && recognizer.recognize(fixedBody).ok()) {
                    changed = true;
                    output.add(lines[i]);
                    for (int k = 0; k < bodyLines.size(); k++) {
                        output.add(MermaidFenceScanner.quotePrefix(lines[i + 1 + k]) + fixedLines[k]);
                    }
                    if (span.nextLine() > bodyEnd) output.add(lines[bodyEnd]);
                    i = span.nextLine();
                    continue;
                }
            }
            output.addAll(Arrays.asList(lines).subList(i, bodyEnd));
            if (span.nextLine() > bodyEnd) output.add(lines[bodyEnd]);
            i = span.nextLine();
        }
        return changed ? String.join(eol, output) : markdownContent;
    }

    private String fixC4Body(String body) {
        String[] lines = body.split("\n", -1);
        String header = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("%%")) continue;
            header = trimmed.split("\\s+", 2)[0];
            break;
        }
        if (!C4_HEADERS.contains(header)) return body;

        boolean changed = false;
        List<String> fixedLines = new ArrayList<>(lines.length);
        for (String line : lines) {
            String fixed = fixC4Line(line);
            if (!fixed.equals(line)) changed = true;
            fixedLines.add(fixed);
        }
        return changed ? String.join("\n", fixedLines) : body;
    }

    private String fixC4Line(String line) {
        int start = 0;
        while (start < line.length() && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) start++;
        String prefix = line.substring(0, start);
        String content = line.substring(start);
        String trimmed = content.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("%%") || trimmed.startsWith("title ") || trimmed.equals("}")) {
            return line;
        }
        int open = content.indexOf('(');
        if (open <= 0) return line;
        String name = content.substring(0, open).trim();
        if (!MermaidRecognizer.C4_ELEMENTS.containsKey(name)) return line;

        int close = -1;
        boolean inQuote = false;
        for (int i = open + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (!inQuote && c == ')') {
                close = i;
                break;
            }
        }
        if (close < 0 || inQuote) return line;
        String args = content.substring(open + 1, close);
        String suffix = content.substring(close + 1);
        if (!suffix.matches("\\s*\\{?\\s*")) return line;

        List<C4Token> tokens = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        int pos = 0;
        while (pos < args.length()) {
            int separatorStart = pos;
            while (pos < args.length() && (args.charAt(pos) == ' ' || args.charAt(pos) == '\t')) pos++;
            if (pos < args.length() && args.charAt(pos) == ',') {
                pos++;
                while (pos < args.length() && (args.charAt(pos) == ' ' || args.charAt(pos) == '\t')) pos++;
            }
            if (pos >= args.length()) break;
            if (!tokens.isEmpty()) separators.add(args.substring(separatorStart, pos));
            if (args.charAt(pos) == '"') {
                int end = args.indexOf('"', pos + 1);
                if (end < 0) return line;
                tokens.add(new C4Token(args.substring(pos, end + 1), true));
                pos = end + 1;
            } else {
                int end = pos;
                while (end < args.length() && (Character.isLetterOrDigit(args.charAt(end)) || args.charAt(end) == '_'))
                    end++;
                if (end == pos) return line;
                tokens.add(new C4Token(args.substring(pos, end), false));
                pos = end;
            }
        }
        if (tokens.isEmpty()) return line;

        int requiredBare = name.startsWith("Rel") ? 2 : 1;
        if (tokens.size() < requiredBare + 1) return line;
        for (int i = 0; i < tokens.size(); i++) {
            boolean expectedBare = i < requiredBare;
            if (tokens.get(i).quoted() == expectedBare) return line;
        }
        boolean inserted = false;
        for (int i = 0; i < separators.size(); i++) {
            if (!separators.get(i).contains(",")) {
                if (!tokens.get(i).quoted() && !tokens.get(i + 1).quoted()) return line;
                inserted = true;
            }
        }
        if (!inserted) return line;

        StringBuilder rebuilt = new StringBuilder(name).append('(');
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) rebuilt.append(", ");
            rebuilt.append(tokens.get(i).text());
        }
        rebuilt.append(')').append(suffix);
        return prefix + rebuilt;
    }

    private record C4Token(String text, boolean quoted) {}
}
