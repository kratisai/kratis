package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.config.IngestionProperties;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class ListRepositoryFilesTool {

    static final int MAX_RESULTS = 300;

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", "node_modules", "target", "build", "dist", "out", "vendor", ".gradle", "bin", "obj");

    private final String baseCloneDir;

    public ListRepositoryFilesTool(IngestionProperties ingestionProperties) {
        this.baseCloneDir = ingestionProperties.getCloneDir();
    }

    @KratisTool(
            name = "list_files",
            description = "List repository file paths matching a glob, for example **/Dockerfile* or"
                    + " .github/workflows/*.yml. Returns relative paths, at most 300.")
    public ListFilesResult listFiles(String glob, ToolContext toolContext) {
        if (glob == null || glob.isBlank()) {
            return ListFilesResult.failure("glob must not be blank.");
        }
        if (glob.contains("..")) {
            return ListFilesResult.failure("Path traversal is not allowed.");
        }
        Object batchIdObj = toolContext != null ? toolContext.getContext().get("batchId") : null;
        if (batchIdObj == null) {
            throw new IllegalStateException("ERROR: batchId not found in tool context.");
        }
        Path root = Path.of(baseCloneDir, UUID.fromString(batchIdObj.toString()).toString());
        Object excludedObj = toolContext.getContext().get("excludedPaths");
        Set<?> excluded = excludedObj instanceof Set<?> set ? set : Set.of();
        List<String> matched = matching(root, List.of(glob)).stream()
                .filter(path -> !excluded.contains(path))
                .toList();
        ListFilesResult result = ListFilesResult.success(matched);
        Object logger = toolContext.getContext().get("batchLogger");
        if (logger instanceof IngestionBatchLogService.BatchLogger batchLogger) {
            batchLogger.info(
                    "ASSIST_SURFACES_LIST_FILES",
                    "glob=" + glob + " results=" + result.paths().size() + " truncated=" + result.truncated());
        }
        return result;
    }

    /** Every regular file under the root, relative and sorted. Skipped directories are excluded. */
    public static List<String> inventory(Path root) {
        List<String> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public @NonNull FileVisitResult preVisitDirectory(
                        @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                    if (!dir.equals(root)
                            && SKIPPED_DIRECTORIES.contains(dir.toFile().getName())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                    files.add(root.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not list repository files: " + e.getMessage(), e);
        }
        files.sort(String::compareTo);
        return files;
    }

    public static List<String> matching(Path root, List<String> globs) {
        List<PathMatcher> matchers = globs.stream()
                .flatMap(glob -> {
                    if (glob.startsWith("**/")) {
                        String rootName = glob.substring(3);
                        return Stream.of(
                                FileSystems.getDefault().getPathMatcher("glob:" + glob),
                                FileSystems.getDefault().getPathMatcher("glob:" + rootName));
                    }
                    return Stream.of(FileSystems.getDefault().getPathMatcher("glob:" + glob));
                })
                .toList();
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return hits;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public @NonNull FileVisitResult preVisitDirectory(
                        @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                    if (!dir.equals(root)
                            && SKIPPED_DIRECTORIES.contains(dir.toFile().getName())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                    Path relative = root.relativize(file);
                    if (matchers.stream().anyMatch(matcher -> matcher.matches(relative))) {
                        hits.add(relative.toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not list repository files: " + e.getMessage(), e);
        }
        hits.sort(String::compareTo);
        return hits;
    }

    public record ListFilesResult(List<String> paths, boolean truncated, String error) {

        static ListFilesResult success(List<String> all) {
            boolean truncated = all.size() > MAX_RESULTS;
            return new ListFilesResult(
                    truncated ? List.copyOf(all.subList(0, MAX_RESULTS)) : List.copyOf(all), truncated, null);
        }

        static ListFilesResult failure(String error) {
            return new ListFilesResult(List.of(), false, error);
        }
    }
}
