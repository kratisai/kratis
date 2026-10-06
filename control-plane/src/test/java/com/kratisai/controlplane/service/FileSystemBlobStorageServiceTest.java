package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemBlobStorageServiceTest {

    @TempDir
    private Path tempDir;

    private FileSystemBlobStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new FileSystemBlobStorageService(tempDir);
    }

    @Test
    void putAndGetBytes_roundtripsCorrectly() {
        byte[] payload = "test-payload".getBytes(StandardCharsets.UTF_8);
        storageService.putObject("test/file.bin", payload, "application/octet-stream");

        assertThat(storageService.exists("test/file.bin")).isTrue();
        assertThat(storageService.getObjectBytes("test/file.bin")).isEqualTo(payload);
    }

    @Test
    void putAndGetStream_roundtripsCorrectly() throws Exception {
        byte[] payload = "stream-payload".getBytes(StandardCharsets.UTF_8);
        storageService.putObject(
                "nested/dir/stream.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

        assertThat(storageService.exists("nested/dir/stream.txt")).isTrue();
        try (InputStream in = storageService.getObject("nested/dir/stream.txt")) {
            assertThat(in).isNotNull();
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void putAndGetText_roundtripsCorrectly() {
        storageService.putText("docs/notes.md", "# Hello World");

        assertThat(storageService.getText("docs/notes.md")).isEqualTo("# Hello World");
    }

    @Test
    void getObject_nonExistent_returnsNull() {
        assertThat(storageService.getObjectBytes("missing.txt")).isNull();
        assertThat(storageService.getObject("missing.txt")).isNull();
        assertThat(storageService.exists("missing.txt")).isFalse();
    }

    @Test
    void deleteObject_removesFile() {
        storageService.putText("to-delete.txt", "bye");
        assertThat(storageService.exists("to-delete.txt")).isTrue();

        storageService.deleteObject("to-delete.txt");
        assertThat(storageService.exists("to-delete.txt")).isFalse();
    }

    @Test
    void resolvePath_traversalAttempt_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> storageService.putText("../outside.txt", "evil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal");
    }
}
