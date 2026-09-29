package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.agentloop.KratisTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReadFileTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    private final String baseCloneDir;

    public ReadFileTool(@Value("${kratis.ingestion.clone-dir:${java.io.tmpdir}/kratis-ingest}") String baseCloneDir) {
        this.baseCloneDir = baseCloneDir;
    }

    @KratisTool(
            name = "read_file",
            description = "Read the source code of a file in the repository by its relative path.")
    public ReadFileResult readFile(String relativePath, ToolContext toolContext) {
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
            return ReadFileResult.failure("File not found: " + relativePath);
        }

        try {
            return ReadFileResult.success(Files.readString(resolvedPath));
        } catch (IOException e) {
            logger.warn("ReadFileTool: failed to read file {}: {}", resolvedPath, e.getMessage());
            return ReadFileResult.failure("Could not read file: " + e.getMessage());
        }
    }

    public record ReadFileResult(
            @Nullable String content, @Nullable String error) {

        static ReadFileResult success(String content) {
            return new ReadFileResult(content, null);
        }

        static ReadFileResult failure(String error) {
            return new ReadFileResult(null, error);
        }
    }
}
