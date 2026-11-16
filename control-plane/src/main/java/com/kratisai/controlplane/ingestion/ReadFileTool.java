package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.config.IngestionProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ReadFileTool {

    static final int MAX_LINES = 500;
    static final int MAX_BATCH_FILES = 10;
    static final int MAX_BATCH_CHARS = 200_000;
    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    private final String baseCloneDir;

    public ReadFileTool(IngestionProperties ingestionProperties) {
        this.baseCloneDir = ingestionProperties.getCloneDir();
    }

    private List<FileRead> readFiles(
            List<String> relativePaths, Integer offset, Integer limit, ToolContext toolContext) {
        List<FileRead> files = new ArrayList<>();
        if (relativePaths == null || relativePaths.isEmpty()) {
            return List.of(new FileRead("", null, "paths must not be empty."));
        }
        int processed = 0;
        int totalChars = 0;
        for (String relativePath : relativePaths) {
            if (processed >= MAX_BATCH_FILES) {
                files.add(new FileRead(
                        relativePath == null ? "" : relativePath,
                        null,
                        "Batch limit reached: read at most " + MAX_BATCH_FILES + " files per call."));
                break;
            }
            ReadFileResult result = readFilePath(relativePath, offset, limit, toolContext);
            String content = result.content();
            int size = content == null ? 0 : content.length();
            if (totalChars + size > MAX_BATCH_CHARS) {
                files.add(new FileRead(
                        relativePath, null, "Batch character budget reached; read this file in a separate call."));
                break;
            }
            files.add(new FileRead(relativePath, content, result.error()));
            totalChars += size;
            processed++;
        }
        return files;
    }

    public record FileRead(
            String relativePath,
            @Nullable String content,
            @Nullable String error) {}

    public ReadFileResult readFile(String relativePath, ToolContext toolContext) {
        return readFilePath(relativePath, null, null, toolContext);
    }

    @KratisTool(
            name = "read_file",
            description =
                    "Read one file by path or up to 10 files by paths. Returns numbered lines, at most 500 lines per file. "
                            + "Use offset and limit to page through longer files.")
    public ReadFileResult readFileInput(
            @ToolParam(description = "Relative path for one file", required = false) @Nullable String relativePath,
            @ToolParam(description = "Relative paths for multiple files, at most 10", required = false) @Nullable
                    List<String> paths,
            @ToolParam(description = "1-based line to start from. Default 1.", required = false) @Nullable
                    Integer offset,
            @ToolParam(description = "Number of lines to return, 1 to 500. Default 500.", required = false) @Nullable
                    Integer limit,
            ToolContext toolContext) {
        boolean hasPath = relativePath != null && !relativePath.isBlank();
        boolean hasPaths = paths != null && !paths.isEmpty();
        if (hasPath == hasPaths) {
            return ReadFileResult.failure("Provide either path or paths, but not both.");
        }
        if (hasPath) {
            return readFilePath(relativePath, offset, limit, toolContext);
        }
        return ReadFileResult.files(readFiles(paths, offset, limit, toolContext));
    }

    private ReadFileResult readFilePath(
            String relativePath, @Nullable Integer offset, @Nullable Integer limit, ToolContext toolContext) {
        if (relativePath == null || relativePath.isBlank()) {
            return ReadFileResult.failure("relativePath must not be blank.");
        }

        // Reject path traversal attempts
        if (relativePath.contains("..")) {
            logger.warn("ReadFileTool: path traversal attempt rejected: {}", relativePath);
            return ReadFileResult.failure("Path traversal is not allowed.");
        }

        Object batchIdObj = toolContext != null ? toolContext.getContext().get("batchId") : null;
        if (batchIdObj == null) {
            throw new IllegalStateException("ERROR: batchId not found in tool context.");
        }

        Path cloneDir;
        try {
            UUID batchId = batchIdObj instanceof UUID ? (UUID) batchIdObj : UUID.fromString(batchIdObj.toString());
            cloneDir = Path.of(baseCloneDir, batchId.toString());
        } catch (IllegalArgumentException e) {
            return ReadFileResult.failure("Invalid batch_id format.");
        }

        Path resolvedPath = cloneDir.resolve(relativePath).normalize();

        // Ensure resolved path is still within the clone directory
        if (!resolvedPath.startsWith(cloneDir.normalize())) {
            logger.warn("ReadFileTool: resolved path {} escapes clone dir {}", resolvedPath, cloneDir);
            return ReadFileResult.failure("Path traversal is not allowed.");
        }

        if (!Files.exists(resolvedPath)) {
            trace(toolContext, "path=" + relativePath + " result=missing");
            return ReadFileResult.failure("File not found: " + relativePath);
        }

        try {
            List<String> lines = Files.readAllLines(resolvedPath);
            trace(
                    toolContext,
                    "path=" + relativePath + " offset=" + offset + " limit=" + limit + " lines=" + lines.size());
            return ReadFileResult.success(page(lines, offset, limit));
        } catch (IOException e) {
            logger.warn("ReadFileTool: failed to read file {}: {}", resolvedPath, e.getMessage());
            return ReadFileResult.failure("Could not read file: " + e.getMessage());
        }
    }

    private static void trace(ToolContext toolContext, String message) {
        if (toolContext == null) {
            return;
        }
        Object logger = toolContext.getContext().get("batchLogger");
        if (logger instanceof IngestionBatchLogService.BatchLogger batchLogger) {
            batchLogger.debug("ASSIST_SURFACES_READ_FILE", message);
        }
    }

    static String page(List<String> lines, @Nullable Integer offset, @Nullable Integer limit) {
        int total = lines.size();
        if (total == 0) {
            return "[empty file]";
        }
        int start = offset == null ? 1 : Math.max(1, offset);
        int count = limit == null ? MAX_LINES : Math.clamp(limit, 1, MAX_LINES);
        if (start > total) {
            return "[offset " + start + " is past the end of the file, which has " + total + " lines]";
        }
        int end = Math.min(total, start + count - 1);
        StringBuilder page = new StringBuilder();
        for (int line = start; line <= end; line++) {
            page.append(line).append(": ").append(lines.get(line - 1)).append('\n');
        }
        page.append("[lines ")
                .append(start)
                .append('-')
                .append(end)
                .append(" of ")
                .append(total);
        if (end < total) {
            page.append("; continue with offset=").append(end + 1);
        }
        return page.append(']').toString();
    }

    public record ReadFileResult(
            @Nullable String content, @Nullable String error, List<FileRead> files) {

        static ReadFileResult success(String content) {
            return new ReadFileResult(content, null, List.of());
        }

        static ReadFileResult failure(String error) {
            return new ReadFileResult(null, error, List.of());
        }

        static ReadFileResult files(List<FileRead> files) {
            return new ReadFileResult(null, null, files);
        }
    }
}
