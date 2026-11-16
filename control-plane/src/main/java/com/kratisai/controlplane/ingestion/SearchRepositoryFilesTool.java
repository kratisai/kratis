package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.config.IngestionProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class SearchRepositoryFilesTool {

    static final int MAX_RESULTS = 40;
    static final int MAX_HITS_PER_FILE = 5;
    static final int MAX_SNIPPET_CHARS = 160;
    static final int MAX_PATTERN_LENGTH = 200;
    static final int MAX_LINE_CHARS = 4096;
    static final int MAX_REGEX_CHAR_OPS = 100_000;
    static final int MAX_TOTAL_CHAR_OPS = 2_000_000;
    private static final int BINARY_SNIFF_BYTES = 4096;

    private final String baseCloneDir;

    public SearchRepositoryFilesTool(IngestionProperties ingestionProperties) {
        this.baseCloneDir = ingestionProperties.getCloneDir();
    }

    @KratisTool(
            name = "search_files",
            description = "Search repository text files for a regex or literal symbol/string (for example an imported"
                    + " SDK hook, client class, topic, or config key found in a manifest or entrypoint). Optional"
                    + " pathGlob narrows candidate paths (for example src/**/*.tsx). Returns at most 40 file:line"
                    + " matches.")
    public SearchFilesResult searchFiles(String pattern, @Nullable String pathGlob, ToolContext toolContext) {
        if (pattern == null || pattern.isBlank()) {
            return SearchFilesResult.failure("pattern must not be blank.");
        }
        if (pathGlob != null && pathGlob.contains("..")) {
            return SearchFilesResult.failure("Path traversal is not allowed.");
        }
        Object batchIdObj = toolContext != null ? toolContext.getContext().get("batchId") : null;
        if (batchIdObj == null) {
            throw new IllegalStateException("ERROR: batchId not found in tool context.");
        }
        Path root = Path.of(baseCloneDir, UUID.fromString(batchIdObj.toString()).toString());
        Object excludedObj = toolContext.getContext().get("excludedPaths");
        Set<?> excluded = excludedObj instanceof Set<?> set ? set : Set.of();

        List<String> candidates = (pathGlob == null || pathGlob.isBlank())
                ? ListRepositoryFilesTool.inventory(root)
                : ListRepositoryFilesTool.matching(root, List.of(pathGlob));

        Pattern compiled = compilePattern(pattern);
        List<SearchHit> hits = new ArrayList<>();
        boolean truncated = false;
        ScanBudget budget = new ScanBudget(MAX_TOTAL_CHAR_OPS);

        for (String relativePath : candidates) {
            if (excluded.contains(relativePath)) {
                continue;
            }
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root) || !isReadableTextFile(file)) {
                continue;
            }
            int fileHits = 0;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    if (budget.exhausted()) {
                        truncated = true;
                        break;
                    }
                    lineNumber++;
                    if (line.indexOf('\0') >= 0) {
                        break;
                    }
                    if (matchesSafely(compiled, pattern, line, budget)) {
                        if (hits.size() >= MAX_RESULTS) {
                            truncated = true;
                            break;
                        }
                        hits.add(new SearchHit(relativePath, lineNumber, trimSnippet(line)));
                        fileHits++;
                        if (fileHits >= MAX_HITS_PER_FILE) {
                            break;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Skip unreadable or non-UTF-8 files.
            }
            if (truncated) {
                break;
            }
        }

        SearchFilesResult result = new SearchFilesResult(List.copyOf(hits), truncated, null);
        Object logger = toolContext.getContext().get("batchLogger");
        if (logger instanceof IngestionBatchLogService.BatchLogger batchLogger) {
            batchLogger.debug(
                    "ASSIST_SURFACES_SEARCH_FILES",
                    "pattern=" + pattern
                            + " glob=" + (pathGlob == null || pathGlob.isBlank() ? "*" : pathGlob)
                            + " matches=" + result.matches().size()
                            + " truncated=" + result.truncated());
        }
        return result;
    }

    public static boolean isReadableTextFile(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
                return false;
            }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] sample = in.readNBytes(BINARY_SNIFF_BYTES);
                for (byte b : sample) {
                    if (b == 0) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Pattern compilePattern(String raw) {
        if (raw.length() > MAX_PATTERN_LENGTH) {
            return Pattern.compile(Pattern.quote(raw));
        }
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException ignored) {
            return Pattern.compile(Pattern.quote(raw));
        }
    }

    private static boolean matchesSafely(Pattern compiled, String rawPattern, String line, ScanBudget budget) {
        String bounded = line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS);
        budget.consumeLineOverhead(bounded.length());
        if (budget.exhausted()) {
            return false;
        }
        int lineOpsBudget = Math.min(MAX_REGEX_CHAR_OPS, budget.remaining());
        StepBoundedCharSequence sequence = new StepBoundedCharSequence(bounded, lineOpsBudget);
        try {
            return compiled.matcher(sequence).find();
        } catch (RegexStepLimitException ignored) {
            return bounded.contains(rawPattern);
        } finally {
            budget.consume(sequence.opsUsed());
        }
    }

    private static String trimSnippet(String line) {
        String stripped = line.strip();
        if (stripped.length() <= MAX_SNIPPET_CHARS) {
            return stripped;
        }
        return stripped.substring(0, MAX_SNIPPET_CHARS) + "...";
    }

    private static final class RegexStepLimitException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class ScanBudget {
        private int remaining;

        ScanBudget(int maxOps) {
            this.remaining = maxOps;
        }

        boolean exhausted() {
            return remaining <= 0;
        }

        int remaining() {
            return Math.max(0, remaining);
        }

        void consumeLineOverhead(int lineLength) {
            remaining -= Math.max(1, lineLength);
        }

        void consume(int ops) {
            remaining -= ops;
        }
    }

    private static final class StepBoundedCharSequence implements CharSequence {
        private final String delegate;
        private final int maxOps;
        private int ops;

        StepBoundedCharSequence(String delegate, int maxOps) {
            this.delegate = delegate;
            this.maxOps = maxOps;
        }

        int opsUsed() {
            return ops;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++ops > maxOps) {
                throw new RegexStepLimitException();
            }
            return delegate.charAt(index);
        }

        @Override
        public @NonNull CharSequence subSequence(int start, int end) {
            return new StepBoundedCharSequence(delegate.substring(start, end), Math.max(0, maxOps - ops));
        }

        @Override
        public @NonNull String toString() {
            return delegate;
        }
    }

    public record SearchHit(String file, int line, String snippet) {}

    public record SearchFilesResult(List<SearchHit> matches, boolean truncated, String error) {
        static SearchFilesResult failure(String error) {
            return new SearchFilesResult(List.of(), false, error);
        }
    }
}
