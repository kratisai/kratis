package com.kratisai.controlplane.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReadFileTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    private final String baseCloneDir;

    public ReadFileTool(@Value("${kratis.ingestion.clone-dir:${java.io.tmpdir}/kratis-ingest}") String baseCloneDir) {
        this.baseCloneDir = baseCloneDir;
    }

    @Tool(name = "read_file", description = "Read the source code of a file in the repository by its relative path.")
    public Map<String, String> readFile(String relativePath, ToolContext toolContext) {
        if (relativePath == null || relativePath.isBlank()) {
            return Map.of("error", "relativePath must not be blank.");
        }

        // Reject path traversal attempts
        if (relativePath.contains("..")) {
            logger.warn("ReadFileTool: path traversal attempt rejected: {}", relativePath);
            return Map.of("error", "Path traversal is not allowed.");
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
            return Map.of("error", "Invalid batch_id format.");
        }

        Path resolvedPath = cloneDir.resolve(relativePath).normalize();

        // Ensure resolved path is still within the clone directory
        if (!resolvedPath.startsWith(cloneDir.normalize())) {
            logger.warn("ReadFileTool: resolved path {} escapes clone dir {}", resolvedPath, cloneDir);
            return Map.of("error", "Path traversal is not allowed.");
        }

        if (!Files.exists(resolvedPath)) {
            return Map.of("error", "File not found: " + relativePath);
        }

        try {
            return Map.of("content", Files.readString(resolvedPath));
        } catch (IOException e) {
            logger.warn("ReadFileTool: failed to read file {}: {}", resolvedPath, e.getMessage());
            return Map.of("error", "Could not read file: " + e.getMessage());
        }
    }
}
