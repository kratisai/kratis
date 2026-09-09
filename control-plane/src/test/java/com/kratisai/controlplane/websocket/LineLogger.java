package com.kratisai.controlplane.websocket;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buffers streaming output chunks and reconstructs complete lines for logging and assertion.
 *
 * <p>Chunks arrive word-by-word from streaming agents. This class buffers partial chunks and
 * emits complete lines only when a newline is encountered.
 */
public class LineLogger {

    private static final Logger logger = LoggerFactory.getLogger(LineLogger.class);

    private final String category;
    private final Map<String, StringBuilder> buffers = new ConcurrentHashMap<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();

    public LineLogger(String category) {
        this.category = category;
    }

    /**
     * Append a chunk of output for the given stream. When a newline is encountered, the complete
     * line is logged, collected for assertions, and the buffer is reset.
     *
     * @param stream the stream identifier (e.g. "stdout", "stderr")
     * @param chunk  the text chunk (may be partial line, complete line, or multiple lines)
     */
    public void appendChunk(String stream, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        StringBuilder buffer = buffers.computeIfAbsent(stream, k -> new StringBuilder());

        // Split on newlines to handle chunks that contain multiple lines
        String[] parts = chunk.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                // Previous part ended with a newline — flush the complete line
                flushLine(buffer, stream);
            }
            buffer.append(parts[i]);
        }
        // Note: if chunk doesn't end with \n, the partial content stays in the buffer
    }

    /**
     * Flush any remaining buffered content for all streams. Call this at the end of a test to
     * ensure no output is lost.
     */
    public void flushAll() {
        for (var entry : buffers.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                flushLine(entry.getValue(), entry.getKey());
            }
        }
    }

    /**
     * Returns all reconstructed complete lines collected so far. Thread-safe.
     *
     * @return an unmodifiable view of the collected lines
     */
    public List<String> getLines() {
        return List.copyOf(lines);
    }

    /**
     * Returns all reconstructed lines joined with newlines. Convenience method for assertions.
     *
     * @return all collected lines joined by newlines
     */
    public String getOutput() {
        return String.join("\n", lines);
    }

    private void flushLine(StringBuilder buffer, String stream) {
        String line = buffer.toString();
        buffer.setLength(0);
        if (!line.isEmpty()) {
            lines.add(line);
            // debug: per-line info logging slows the fixture's socket reader and starves the fan-out.
            logger.debug("[{}][{}] {}", category, stream, line);
        }
    }
}
