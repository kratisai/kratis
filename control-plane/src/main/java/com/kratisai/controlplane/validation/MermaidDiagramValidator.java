package com.kratisai.controlplane.validation;

import com.kratisai.controlplane.validation.mermaid.MermaidGrammar;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognition;
import com.kratisai.controlplane.validation.mermaid.MermaidRecognizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MermaidDiagramValidator {
    public record DiagramRepairRequest(int diagramNumber, String source, List<String> issues, boolean unclosed) {}

    private static final Pattern OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern NESTED_FENCE = Pattern.compile("^[\\s>+*\\-\\d.)]*(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^([ \\t]*)([-+*]|[0-9]{1,9}[.)])([ \\t]+)(.*)$");
    private static final long REPAIR_TIMEOUT_SECONDS = 60;
    private final MermaidGrammar grammar = MermaidGrammar.load();
    private final MermaidRecognizer recognizer = new MermaidRecognizer(grammar);

    public String normalizeFences(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) return markdownContent;
        String eol = markdownContent.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = markdownContent.split("\\r\\n|\\n|\\r", -1);
        List<String> output = new ArrayList<>(lines.length);
        boolean changed = false;
        int i = 0;
        while (i < lines.length) {
            int currentQuoteDepth = quoteDepth(lines[i]);
            String line = stripBlockQuote(lines[i]);
            Matcher open = OPEN.matcher(line);
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
            FenceSpan span = findFenceSpan(lines, i + 1, fence, currentQuoteDepth);
            int bodyEnd = span.bodyEnd();
            String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            boolean headerAsFence = grammar.isHeaderKeyword(firstToken);
            boolean genericFence = firstToken.isEmpty() || firstToken.equals("text");

            if (headerAsFence || (genericFence && bodyStartsWithHeader(lines, i + 1, bodyEnd))) {
                changed = true;
                String prefix = quotePrefix(lines[i]);
                String openIndent = line.substring(0, open.start(1));
                output.add(prefix + openIndent + fence + "mermaid");
                if (headerAsFence && !bodyStartsWithHeader(lines, i + 1, bodyEnd)) {
                    output.add(prefix + info);
                }
            } else {
                output.add(lines[i]);
            }
            for (int j = i + 1; j < bodyEnd; j++) {
                output.add(lines[j]);
            }
            if (span.nextLine() > bodyEnd) {
                output.add(lines[bodyEnd]);
            }
            i = span.nextLine();
        }
        return changed ? String.join(eol, output) : markdownContent;
    }

    /**
     * Repairs invalid Mermaid diagram blocks in {@code markdownContent} using {@code repairFn}.
     * Callers should run {@link #normalizeFences(String)} first so header-tagged and generic diagram
     * fences are normalized to {@code mermaid} before repair.
     */
    public String repairDiagrams(
            String markdownContent, Function<DiagramRepairRequest, String> repairFn, Executor executor) {
        if (markdownContent == null || markdownContent.isBlank()) return markdownContent;
        String eol = markdownContent.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = markdownContent.split("\\r\\n|\\n|\\r", -1);
        List<InvalidBlock> invalidBlocks = collectInvalidBlocks(lines);
        if (invalidBlocks.isEmpty()) return markdownContent;

        Map<Integer, String> repairedBodies = new HashMap<>();
        if (invalidBlocks.size() == 1) {
            InvalidBlock block = invalidBlocks.getFirst();
            String body = extractValidBody(repairFn.apply(block.request()));
            if (body != null) repairedBodies.put(block.startLine(), body);
        } else {
            List<CompletableFuture<Void>> futures = new ArrayList<>(invalidBlocks.size());
            Map<Integer, String> concurrentResults = new ConcurrentHashMap<>();
            for (InvalidBlock block : invalidBlocks) {
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            String body = extractValidBody(repairFn.apply(block.request()));
                            if (body != null) concurrentResults.put(block.startLine(), body);
                        },
                        executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .get(REPAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // Keep any repairs that already completed in concurrentResults.
            }
            repairedBodies.putAll(concurrentResults);
        }
        if (repairedBodies.isEmpty()) return markdownContent;

        return spliceRepairedBlocks(lines, eol, invalidBlocks, repairedBodies);
    }

    private record FenceSpan(int bodyEnd, int nextLine, boolean unclosed) {}

    private record InvalidBlock(int startLine, int bodyEnd, int nextLine, String fence, DiagramRepairRequest request) {}

    private List<InvalidBlock> collectInvalidBlocks(String[] lines) {
        List<InvalidBlock> invalidBlocks = new ArrayList<>();
        int diagram = 0;
        int i = 0;
        while (i < lines.length) {
            int currentQuoteDepth = quoteDepth(lines[i]);
            String line = stripBlockQuote(lines[i]);
            Matcher open = OPEN.matcher(line);
            if (!open.matches()) {
                i++;
                continue;
            }
            String fence = open.group(1);
            String info = open.group(2).trim();
            if (fence.charAt(0) == '`' && info.contains("`")) {
                i++;
                continue;
            }
            FenceSpan span = findFenceSpan(lines, i + 1, fence, currentQuoteDepth);
            int bodyEnd = span.bodyEnd();
            String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            if (!firstToken.equals("mermaid")) {
                boolean isHeaderTag = grammar.isHeaderKeyword(firstToken);
                boolean isGenericTag = firstToken.isEmpty() || firstToken.equals("text");
                if (isHeaderTag || (isGenericTag && bodyStartsWithHeader(lines, i + 1, bodyEnd))) {
                    diagram++;
                }
                i = span.nextLine();
                continue;
            }
            diagram++;
            StringBuilder source = new StringBuilder();
            for (int j = i + 1; j < bodyEnd; j++) {
                source.append(stripBlockQuote(lines[j])).append('\n');
            }
            boolean unclosed = span.unclosed();
            List<String> blockIssues = new ArrayList<>();
            if (unclosed) {
                blockIssues.add(
                        "Mermaid diagram #%d, line 1: unclosed fence at Markdown line %d. Close the fence and retry."
                                .formatted(diagram, i + 1));
            } else {
                addRecognitionIssue(blockIssues, diagram, source);
            }
            if (!blockIssues.isEmpty()) {
                invalidBlocks.add(new InvalidBlock(
                        i,
                        bodyEnd,
                        span.nextLine(),
                        fence,
                        new DiagramRepairRequest(diagram, source.toString(), List.copyOf(blockIssues), unclosed)));
            }
            i = span.nextLine();
        }
        return invalidBlocks;
    }

    private String extractValidBody(String repaired) {
        String body = extractMermaidBody(repaired);
        return body != null && recognizer.recognize(body).ok() ? body : null;
    }

    private String spliceRepairedBlocks(
            String[] lines, String eol, List<InvalidBlock> invalidBlocks, Map<Integer, String> repairedBodies) {
        Map<Integer, InvalidBlock> byStart = new HashMap<>();
        for (InvalidBlock block : invalidBlocks) {
            if (repairedBodies.containsKey(block.startLine())) {
                byStart.put(block.startLine(), block);
            }
        }
        List<String> output = new ArrayList<>(lines.length);
        int i = 0;
        while (i < lines.length) {
            InvalidBlock block = byStart.get(i);
            if (block == null) {
                output.add(lines[i]);
                i++;
                continue;
            }
            String prefix = quotePrefix(lines[i]);
            output.add(lines[i]);
            for (String bodyLine : repairedBodies.get(i).split("\\r\\n|\\n|\\r", -1)) {
                output.add(prefix + bodyLine);
            }
            output.add(prefix + block.fence());
            i = block.nextLine();
        }
        return String.join(eol, output);
    }

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
            int currentQuoteDepth = quoteDepth(lines[i]);
            String line = stripBlockQuote(lines[i]);
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
                Matcher open = OPEN.matcher(line);
                if (!open.matches()) {
                    Matcher nested = NESTED_FENCE.matcher(line);
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
            } else if (isClosingFence(line, fence, nestedFence)) {
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
                    || firstBodyTokenIsHeader(source.toString())) {
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
        boolean bodyHasHeader = firstBodyTokenIsHeader(source.toString());
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

    private FenceSpan findFenceSpan(String[] lines, int start, String fence, int openQuoteDepth) {
        for (int j = start; j < lines.length; j++) {
            if (quoteDepth(lines[j]) < openQuoteDepth) return new FenceSpan(j, j, false);
            if (isClosingFence(stripBlockQuote(lines[j]), fence, false)) return new FenceSpan(j, j + 1, false);
        }
        return new FenceSpan(lines.length, lines.length, true);
    }

    private boolean bodyStartsWithHeader(String[] lines, int start, int end) {
        for (int j = start; j < end; j++) {
            String stripped = stripBlockQuote(lines[j]).trim();
            if (stripped.isEmpty() || stripped.startsWith("%%")) continue;
            return grammar.isHeaderKeyword(stripped.split("\\s+", 2)[0]);
        }
        return false;
    }

    private boolean firstBodyTokenIsHeader(String body) {
        for (String line : body.split("\\r\\n|\\n|\\r", -1)) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.startsWith("%%")) continue;
            return grammar.isHeaderKeyword(stripped.split("\\s+", 2)[0]);
        }
        return false;
    }

    private String extractMermaidBody(String repaired) {
        if (repaired == null || repaired.isBlank()) return null;
        String trimmed = repaired.trim();
        String[] lines = trimmed.split("\\r\\n|\\n|\\r", -1);
        Matcher open = OPEN.matcher(lines[0]);
        if (open.matches()) {
            String fence = open.group(1);
            StringBuilder body = new StringBuilder();
            for (int j = 1; j < lines.length; j++) {
                if (isClosingFence(lines[j], fence, false)) {
                    return body.toString().stripTrailing();
                }
                if (body.length() > 0) body.append('\n');
                body.append(lines[j]);
            }
            return body.toString().stripTrailing();
        }
        return trimmed;
    }

    private static String quotePrefix(String line) {
        String result = line;
        int consumed = 0;
        while (true) {
            Matcher prefix = Pattern.compile("^ {0,3}> ?").matcher(result);
            if (!prefix.find()) return line.substring(0, consumed);
            consumed += prefix.end();
            result = result.substring(prefix.end());
        }
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
