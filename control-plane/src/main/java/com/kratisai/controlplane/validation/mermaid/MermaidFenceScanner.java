package com.kratisai.controlplane.validation.mermaid;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MermaidFenceScanner {

    public static final Pattern OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    public static final Pattern NESTED_FENCE = Pattern.compile("^[\\s>+*\\-\\d.)]*(`{3,}|~{3,})([^\\r\\n]*)$");
    public static final Pattern LIST_ITEM = Pattern.compile("^([ \\t]*)([-+*]|[0-9]{1,9}[.)])([ \\t]+)(.*)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^ {0,3}> ?");

    private MermaidFenceScanner() {}

    public record FenceSpan(int bodyEnd, int nextLine, boolean unclosed) {}

    public static int quoteDepth(String line) {
        int depth = 0;
        String result = line;
        while (true) {
            Matcher prefix = BLOCKQUOTE.matcher(result);
            if (!prefix.find()) return depth;
            result = result.substring(prefix.end());
            depth++;
        }
    }

    public static String stripBlockQuote(String line) {
        String result = line;
        while (true) {
            Matcher prefix = BLOCKQUOTE.matcher(result);
            if (!prefix.find()) return result;
            result = result.substring(prefix.end());
        }
    }

    public static String quotePrefix(String line) {
        String result = line;
        int consumed = 0;
        while (true) {
            Matcher prefix = BLOCKQUOTE.matcher(result);
            if (!prefix.find()) return line.substring(0, consumed);
            consumed += prefix.end();
            result = result.substring(prefix.end());
        }
    }

    public static boolean isClosingFence(String line, String fence, boolean nestedFence) {
        String stripped = line.stripLeading();
        if ((!nestedFence && line.length() - stripped.length() > 3)
                || stripped.isEmpty()
                || stripped.charAt(0) != fence.charAt(0)) {
            return false;
        }
        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == fence.charAt(0)) count++;
        return count >= fence.length() && stripped.substring(count).isBlank();
    }

    public static FenceSpan findFenceSpan(String[] lines, int start, String fence, int openQuoteDepth) {
        for (int j = start; j < lines.length; j++) {
            if (quoteDepth(lines[j]) < openQuoteDepth) return new FenceSpan(j, j, false);
            if (isClosingFence(stripBlockQuote(lines[j]), fence, false)) return new FenceSpan(j, j + 1, false);
        }
        return new FenceSpan(lines.length, lines.length, true);
    }

    public static boolean bodyStartsWithHeader(String[] lines, int start, int end, MermaidGrammar grammar) {
        for (int j = start; j < end; j++) {
            String stripped = stripBlockQuote(lines[j]).trim();
            if (stripped.isEmpty() || stripped.startsWith("%%")) continue;
            return grammar.isHeaderKeyword(stripped.split("\\s+", 2)[0]);
        }
        return false;
    }

    public static boolean firstBodyTokenIsHeader(String body, MermaidGrammar grammar) {
        for (String line : body.split("\\r\\n|\\n|\\r", -1)) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.startsWith("%%")) continue;
            return grammar.isHeaderKeyword(stripped.split("\\s+", 2)[0]);
        }
        return false;
    }
}
