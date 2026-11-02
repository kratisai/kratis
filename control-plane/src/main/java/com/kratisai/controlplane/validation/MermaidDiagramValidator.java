package com.kratisai.controlplane.validation;

import com.kratisai.controlplane.validation.mermaid.MermaidGrammar;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognition;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MermaidDiagramValidator {
    private static final Pattern OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern NESTED_FENCE = Pattern.compile("^[\\s>+*\\-\\d.)]*(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^([ \\t]*)([-+*]|[0-9]{1,9}[.)])([ \\t]+)(.*)$");
    private final MermaidRecognizer recognizer = new MermaidRecognizer(MermaidGrammar.load());

    public List<String> findIssues(String markdownContent) {
        List<String> issues = new ArrayList<>();
        if (markdownContent == null || markdownContent.isBlank()) return issues;
        String[] lines = markdownContent.split("\\r\\n|\\n|\\r", -1);
        String fence = null;
        boolean mermaid = false;
        boolean nestedFence = false;
        int diagram = 0;
        int openingLine = 0;
        int quoteDepth = 0;
        StringBuilder source = new StringBuilder();
        ListContext lists = new ListContext();
        for (int i = 0; i < lines.length; i++) {
            int currentQuoteDepth = quoteDepth(lines[i]);
            String line = stripBlockQuote(lines[i]);
            if (fence != null
                    && (currentQuoteDepth < quoteDepth
                            || (nestedFence && !line.isBlank() && !Character.isWhitespace(line.charAt(0))))) {
                if (mermaid) addRecognitionIssue(issues, diagram, source);
                fence = null;
            }
            if (fence == null) {
                lists.observe(line, currentQuoteDepth);
                Matcher open = OPEN.matcher(line);
                if (!open.matches()) {
                    Matcher nested = NESTED_FENCE.matcher(line);
                    if (nested.matches() && lists.containsFence(line, nested.start(1))) {
                        String info = nested.group(2).trim();
                        if (!info.isEmpty() && info.split("\\s+", 2)[0].equals("mermaid")) {
                            diagram++;
                            issues.add(
                                    "Mermaid diagram #%d, line 1: nested fence at Markdown line %d cannot be validated. Move it to a top-level fence and retry."
                                            .formatted(diagram, i + 1));
                            return issues;
                        }
                        fence = nested.group(1);
                        quoteDepth = currentQuoteDepth;
                        nestedFence = true;
                        mermaid = false;
                    }
                    continue;
                }
                String info = open.group(2).trim();
                if (open.group(1).charAt(0) == '`' && info.contains("`")) continue;
                fence = open.group(1);
                quoteDepth = currentQuoteDepth;
                nestedFence = false;
                mermaid = !info.isEmpty() && info.split("\\s+", 2)[0].equals("mermaid");
                if (mermaid) {
                    diagram++;
                    openingLine = i + 1;
                    source.setLength(0);
                }
            } else if (isClosingFence(line, fence, nestedFence)) {
                if (mermaid) addRecognitionIssue(issues, diagram, source);
                fence = null;
            } else if (mermaid) {
                source.append(line).append('\n');
            }
        }
        if (fence != null && mermaid) {
            issues.add("Mermaid diagram #%d, line 1: unclosed fence at Markdown line %d. Close the fence and retry."
                    .formatted(diagram, openingLine));
        }
        return issues;
    }

    private static final class ListContext {
        private final List<Integer> indents = new ArrayList<>();
        private int quoteDepth;
        private boolean blank;

        void observe(String line, int currentQuoteDepth) {
            if (!indents.isEmpty() && currentQuoteDepth != quoteDepth) indents.clear();
            if (line.isBlank()) {
                blank = true;
                return;
            }
            Matcher item = LIST_ITEM.matcher(line);
            int indentation = columns(line, leadingWhitespace(line));
            if (item.matches() && isValidItemIndent(indentation)) {
                recordItems(line, item, currentQuoteDepth);
            } else if (!indents.isEmpty()) {
                if (indentation < indents.getFirst()
                        && (blank
                                || line.stripLeading().startsWith("```")
                                || line.stripLeading().startsWith("~~~"))) {
                    indents.clear();
                } else if (indentation >= indents.getFirst()) {
                    while (indents.size() > 1 && indentation < indents.getLast()) indents.removeLast();
                }
            }
            blank = false;
        }

        private void recordItems(String line, Matcher item, int currentQuoteDepth) {
            int offset = 0;
            do {
                int indentation = columns(line, offset + item.start(2));
                if (!isValidItemIndent(indentation)) return;
                while (!indents.isEmpty() && indentation < indents.getLast()) indents.removeLast();
                int markerEnd = indentation + item.group(2).length();
                int padding = columns(line, offset + item.start(4)) - markerEnd;
                indents.add(markerEnd + (padding > 4 ? 1 : padding));
                quoteDepth = currentQuoteDepth;
                offset += item.start(4);
                item = LIST_ITEM.matcher(line.substring(offset));
            } while (item.matches());
        }

        boolean containsFence(String line, int fenceStart) {
            if (indents.isEmpty()) return false;
            int quoteStart = line.indexOf('>');
            int prefixEnd = quoteStart >= 0 && quoteStart < fenceStart ? quoteStart : fenceStart;
            int indentation = columns(line, prefixEnd);
            return indentation >= indents.getLast() && indentation <= indents.getLast() + 3;
        }

        private boolean isValidItemIndent(int indentation) {
            if (indentation <= 3) return true;
            for (int indent : indents) {
                if (indentation >= indent && indentation <= indent + 3) return true;
            }
            return false;
        }

        private static int leadingWhitespace(String line) {
            int end = 0;
            while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) end++;
            return end;
        }

        private static int columns(String line, int end) {
            int column = 0;
            for (int i = 0; i < end; i++) {
                column += line.charAt(i) == '\t' ? 4 - column % 4 : 1;
            }
            return column;
        }
    }

    private void addRecognitionIssue(List<String> issues, int diagram, StringBuilder source) {
        MermaidRecognition result = recognizer.recognize(source.toString());
        if (!result.ok()) {
            issues.add("Mermaid diagram #%d, %s (%s). Fix the reported line and resend the complete content."
                    .formatted(diagram, result.error(), result.rule()));
        }
    }

    private static int quoteDepth(String line) {
        int depth = 0;
        String result = line;
        while (true) {
            Matcher prefix = Pattern.compile("^ {0,3}> ?").matcher(result);
            if (!prefix.find()) return depth;
            result = result.substring(prefix.end());
            depth++;
        }
    }

    private static String stripBlockQuote(String line) {
        String result = line;
        while (true) {
            Matcher prefix = Pattern.compile("^ {0,3}> ?").matcher(result);
            if (!prefix.find()) return result;
            result = result.substring(prefix.end());
        }
    }

    private static boolean isClosingFence(String line, String fence, boolean nestedFence) {
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
}
