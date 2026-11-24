package com.kratisai.controlplane.validation.mermaid;

import com.kratisai.controlplane.validation.mermaid.MermaidFenceScanner.FenceSpan;
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
import org.springframework.stereotype.Component;

@Component
public class MermaidDiagramRepairer {
    public record DiagramRepairRequest(int diagramNumber, String source, List<String> issues, boolean unclosed) {}

    private static final long REPAIR_TIMEOUT_SECONDS = 60;

    private final MermaidNormalizer normalizer;
    private final MermaidGrammar grammar = MermaidGrammar.load();
    private final MermaidRecognizer recognizer = new MermaidRecognizer(grammar);

    public MermaidDiagramRepairer(MermaidNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Normalizes deterministic issues first, then repairs remaining invalid Mermaid blocks in
     * {@code markdownContent} using {@code repairFn}.
     */
    public String repairDiagrams(
            String markdownContent, Function<DiagramRepairRequest, String> repairFn, Executor executor) {
        if (markdownContent == null || markdownContent.isBlank()) return markdownContent;
        String normalized = normalizer.normalize(markdownContent);
        String eol = normalized.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = normalized.split("\\r\\n|\\n|\\r", -1);
        List<InvalidBlock> invalidBlocks = collectInvalidBlocks(lines);
        if (invalidBlocks.isEmpty()) return normalized;

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
        if (repairedBodies.isEmpty()) return normalized;

        return spliceRepairedBlocks(lines, eol, invalidBlocks, repairedBodies);
    }

    private record InvalidBlock(int startLine, int bodyEnd, int nextLine, String fence, DiagramRepairRequest request) {}

    private List<InvalidBlock> collectInvalidBlocks(String[] lines) {
        List<InvalidBlock> invalidBlocks = new ArrayList<>();
        int diagram = 0;
        int i = 0;
        while (i < lines.length) {
            int currentQuoteDepth = MermaidFenceScanner.quoteDepth(lines[i]);
            String line = MermaidFenceScanner.stripBlockQuote(lines[i]);
            Matcher open = MermaidFenceScanner.OPEN.matcher(line);
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
            FenceSpan span = MermaidFenceScanner.findFenceSpan(lines, i + 1, fence, currentQuoteDepth);
            int bodyEnd = span.bodyEnd();
            String firstToken = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            if (!firstToken.equals("mermaid")) {
                boolean isHeaderTag = grammar.isHeaderKeyword(firstToken);
                boolean isGenericTag = firstToken.isEmpty() || firstToken.equals("text");
                if (isHeaderTag
                        || (isGenericTag && MermaidFenceScanner.bodyStartsWithHeader(lines, i + 1, bodyEnd, grammar))) {
                    diagram++;
                }
                i = span.nextLine();
                continue;
            }
            diagram++;
            StringBuilder source = new StringBuilder();
            for (int j = i + 1; j < bodyEnd; j++) {
                source.append(MermaidFenceScanner.stripBlockQuote(lines[j])).append('\n');
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

    private void addRecognitionIssue(List<String> issues, int diagram, StringBuilder source) {
        MermaidRecognition result = recognizer.recognize(source.toString());
        if (!result.ok()) {
            issues.add("Mermaid diagram #%d, %s (%s). Fix the reported line and resend the complete content."
                    .formatted(diagram, result.error(), result.rule()));
        }
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
            String prefix = MermaidFenceScanner.quotePrefix(lines[i]);
            output.add(lines[i]);
            for (String bodyLine : repairedBodies.get(i).split("\\r\\n|\\n|\\r", -1)) {
                output.add(prefix + bodyLine);
            }
            output.add(prefix + block.fence());
            i = block.nextLine();
        }
        return String.join(eol, output);
    }

    private String extractMermaidBody(String repaired) {
        if (repaired == null || repaired.isBlank()) return null;
        String trimmed = repaired.trim();
        String[] lines = trimmed.split("\\r\\n|\\n|\\r", -1);
        Matcher open = MermaidFenceScanner.OPEN.matcher(lines[0]);
        if (open.matches()) {
            String fence = open.group(1);
            StringBuilder body = new StringBuilder();
            for (int j = 1; j < lines.length; j++) {
                if (MermaidFenceScanner.isClosingFence(lines[j], fence, false)) {
                    return body.toString().stripTrailing();
                }
                if (!body.isEmpty()) body.append('\n');
                body.append(lines[j]);
            }
            return body.toString().stripTrailing();
        }
        return trimmed;
    }
}
