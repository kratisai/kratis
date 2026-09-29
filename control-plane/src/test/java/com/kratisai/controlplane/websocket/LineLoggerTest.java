package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LineLoggerTest {

    @Test
    void buffersPartialLinesUntilNewline() {
        var lineLogger = new LineLogger("test");

        // These should be buffered, not yet emitted
        lineLogger.appendChunk("stdout", "Hello ");
        lineLogger.appendChunk("stdout", "World");

        // flushAll should emit the partial content
        lineLogger.flushAll();
    }

    @Test
    void handlesMultipleLinesInSingleChunk() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "line1\nline2\nline3\n");

        lineLogger.flushAll();
    }

    @Test
    void handlesMultipleStreamsIndependently() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "out1\n");
        lineLogger.appendChunk("stderr", "err1\n");
        lineLogger.appendChunk("stdout", "out2\n");

        lineLogger.flushAll();
    }

    @Test
    void handlesEmptyAndNullChunks() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "");
        lineLogger.appendChunk("stdout", null);
        lineLogger.appendChunk("stdout", "valid\n");

        lineLogger.flushAll();
    }

    @Test
    void handlesChunkEndingWithNewline() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "complete line\n");

        lineLogger.flushAll(); // Should be a no-op since buffer is empty
    }

    @Test
    void handlesChunkStartingWithNewline() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "partial");
        lineLogger.appendChunk("stdout", "\nnext line\n");

        lineLogger.flushAll();
    }

    @Test
    void constructorAcceptsCategory() {
        assertThat(new LineLogger("execution")).isNotNull();
        assertThat(new LineLogger("setup")).isNotNull();
        assertThat(new LineLogger("")).isNotNull();
    }

    @Test
    void multipleFlushesAreIdempotent() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "line\n");
        lineLogger.flushAll();
        lineLogger.flushAll();
        lineLogger.flushAll();
    }

    @Test
    void handlesSplitNewlinesAcrossChunks() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "first line");
        lineLogger.appendChunk("stdout", "\nsecond line\n");

        lineLogger.flushAll();
    }

    @Test
    void handlesInterleavedStreams() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "out");
        lineLogger.appendChunk("stderr", "err");
        lineLogger.appendChunk("stdout", "put\n");
        lineLogger.appendChunk("stderr", "or\n");

        lineLogger.flushAll();
    }

    @Test
    void handlesVeryLongLine() {
        var lineLogger = new LineLogger("test");

        String longLine = "x".repeat(100_000) + "\n";
        lineLogger.appendChunk("stdout", longLine);

        lineLogger.flushAll();
    }

    @Test
    void handlesUnicodeContent() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "Hello 世界 🌍\n");

        lineLogger.flushAll();
    }

    @Test
    void handlesOnlyNewlines() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "\n\n\n");

        lineLogger.flushAll();
    }

    @Test
    void getLinesReturnsReconstructedLines() {
        var lineLogger = new LineLogger("test");

        // Simulate word-by-word streaming
        lineLogger.appendChunk("stdout", "Hello ");
        lineLogger.appendChunk("stdout", "World");
        lineLogger.appendChunk("stdout", "\n");
        lineLogger.appendChunk("stdout", "Second ");
        lineLogger.appendChunk("stdout", "line\n");

        List<String> lines = lineLogger.getLines();
        assertThat(lines).containsExactly("Hello World", "Second line");
    }

    @Test
    void getOutputReturnsJoinedLines() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "line1\n");
        lineLogger.appendChunk("stdout", "line2\n");
        lineLogger.appendChunk("stderr", "err1\n");

        String output = lineLogger.getOutput();
        assertThat(output).isEqualTo("line1\nline2\nerr1");
    }

    @Test
    void getLinesIncludesFlushedPartialContent() {
        var lineLogger = new LineLogger("test");

        lineLogger.appendChunk("stdout", "partial content");
        // No newline yet — content is buffered

        assertThat(lineLogger.getLines()).isEmpty();

        lineLogger.flushAll();

        assertThat(lineLogger.getLines()).containsExactly("partial content");
    }

    @Test
    void getLinesIsEmptyInitially() {
        var lineLogger = new LineLogger("test");
        assertThat(lineLogger.getLines()).isEmpty();
        assertThat(lineLogger.getOutput()).isEmpty();
    }
}
