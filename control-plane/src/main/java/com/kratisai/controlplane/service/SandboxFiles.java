package com.kratisai.controlplane.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Materialises files into a sandbox by streaming base64 through {@code env.exec}. Commands are
 * chunked so each stays well below shell and JSON-RPC argument limits, and base64 output is purely
 * shell-safe inside single quotes.
 */
final class SandboxFiles {

    private static final int WRITE_CHUNK_CHARS = 8000;

    private SandboxFiles() {}

    /**
     * Reads a file from the harness catalogue. {@code source} is relative to {@code harnessDirectory};
     * escapes outside it are rejected so a definition cannot pull arbitrary host files into a sandbox.
     */
    static String readHarnessFile(Path harnessDirectory, String source) {
        Path base = harnessDirectory.toAbsolutePath().normalize();
        Path path = base.resolve(source).normalize();
        if (!path.startsWith(base)) {
            throw new IllegalStateException("Harness resource escapes the harness directory: " + source);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("Missing harness resource " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read harness resource " + path, e);
        }
    }

    /** Commands that write {@code content} to {@code targetPath} inside the sandbox. */
    static List<String> writeCommands(String targetPath, String content) {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        if (encoded.isEmpty()) {
            return List.of(": > " + targetPath);
        }
        List<String> commands = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < encoded.length(); i += WRITE_CHUNK_CHARS) {
            String chunk = encoded.substring(i, Math.min(i + WRITE_CHUNK_CHARS, encoded.length()));
            commands.add("echo '" + chunk + "' | base64 -d " + (first ? ">" : ">>") + " " + targetPath);
            first = false;
        }
        return List.copyOf(commands);
    }
}
