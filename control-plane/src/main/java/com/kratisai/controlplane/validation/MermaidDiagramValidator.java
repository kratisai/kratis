package com.kratisai.controlplane.validation;

import com.kratisai.controlplane.validation.mermaid.MermaidFenceScanner;
import com.kratisai.controlplane.validation.mermaid.MermaidGrammar;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognition;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

@Component
public class MermaidDiagramValidator {
    private final MermaidGrammar grammar = MermaidGrammar.load();
    private final MermaidRecognizer recognizer = new MermaidRecognizer(grammar);

    public List<String> findIssues(String markdownContent) {
        List<String> issues = new ArrayList<>();
        if (markdownContent == null || markdownContent.isBlank()) return issues;
        String[] lines = markdownContent.split("\\r\\n|\\n|\\r", -1);
        String fence = null;
        boolean mermaid = false;
        boolean nestedFence = false;
        String nonMermaidHeaderInfo = null;
        int diagram = 0;
        int openingLine = 0;
        int quoteDepth = 0;
        StringBuilder source = new StringBuilder();
        ListContext lists = new ListContext();
        for (int i = 0; i < lines.length; i++) {
            int currentQuoteDepth = MermaidFenceScanner.quoteDepth(lines[i]);
            String line = MermaidFenceScanner.stripBlockQuote(lines[i]);
            if (fence != null
                    && (currentQuoteDepth < quoteDepth
                            || (nestedFence && !line.isBlank() && !Character.isWhitespace(line.charAt(0))))) {
                if (mermaid) {
                    if (!validateBlock(issues, diagram, openingLine, nonMermaidHeaderInfo, source)) {
                        diagram--;
                    }
                }
                fence = null;
                nonMermaidHeaderInfo = null;
            }
            if (fence == null) {
                lists.observe(line, currentQuoteDepth);
                Matcher open = MermaidFenceScanner.OPEN.matcher(line);
                if (!open.matches()) {
                    Matcher nested = MermaidFenceScanner.NESTED_FENCE.matcher(line);
                    if (nested.matches() && lists.containsFence(line, nested.start(1))) {
                        String info = nested.group(2).trim();
                        String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
                        if (firstToken.equals("mermaid") || grammar.isHeaderKeyword(firstToken)) {
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
                String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
                boolean isMermaidTag = firstToken.equals("mermaid");
                boolean isHeaderTag = grammar.isHeaderKeyword(firstToken);
                boolean isGenericTag = firstToken.isEmpty() || firstToken.equals("text");
                mermaid = isMermaidTag || isHeaderTag || isGenericTag;
                nonMermaidHeaderInfo = isMermaidTag ? null : info;
                if (mermaid) {
                    diagram++;
                    openingLine = i + 1;
                    source.setLength(0);
                }
            } else if (MermaidFenceScanner.isClosingFence(line, fence, nestedFence)) {
                if (mermaid) {
                    if (!validateBlock(issues, diagram, openingLine, nonMermaidHeaderInfo, source)) {
                        diagram--;
                    }
                }
                fence = null;
                nonMermaidHeaderInfo = null;
            } else if (mermaid) {
                source.append(line).append('\n');
            }
        }
        if (fence != null && mermaid) {
            if (nonMermaidHeaderInfo == null
                    || grammar.isHeaderKeyword(nonMermaidHeaderInfo.split("\\s+", 2)[0])
                    || MermaidFenceScanner.firstBodyTokenIsHeader(source.toString(), grammar)) {
                issues.add("Mermaid diagram #%d, line 1: unclosed fence at Markdown line %d. Close the fence and retry."
                        .formatted(diagram, openingLine));
            }
        }
        return issues;
    }

    private boolean validateBlock(
            List<String> issues, int diagram, int openingLine, String nonMermaidHeaderInfo, StringBuilder source) {
        if (nonMermaidHeaderInfo == null) {
            addRecognitionIssue(issues, diagram, source);
            return true;
        }
        String firstToken =
                nonMermaidHeaderInfo.isEmpty() ? "" : nonMermaidHeaderInfo.split("\\s+", 2)[0];
        boolean isHeaderTag = grammar.isHeaderKeyword(firstToken);
        boolean bodyHasHeader = MermaidFenceScanner.firstBodyTokenIsHeader(source.toString(), grammar);
        if (!isHeaderTag && !bodyHasHeader) {
            return false;
        }
        String found = nonMermaidHeaderInfo.isEmpty() ? "(empty)" : nonMermaidHeaderInfo;
        issues.add(
                "Mermaid diagram #%d, line 1: code fence at Markdown line %d must use 'mermaid' as the fence language (found '%s'). Put the diagram header on line 1 inside the block and retry."
                        .formatted(diagram, openingLine, found));
        StringBuilder effective = isHeaderTag && !bodyHasHeader
                ? new StringBuilder(nonMermaidHeaderInfo).append('\n').append(source)
                : source;
        addRecognitionIssue(issues, diagram, effective);
        return true;
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
            Matcher item = MermaidFenceScanner.LIST_ITEM.matcher(line);
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
                item = MermaidFenceScanner.LIST_ITEM.matcher(line.substring(offset));
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
}
