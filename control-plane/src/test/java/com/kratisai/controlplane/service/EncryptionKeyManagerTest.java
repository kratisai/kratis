package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptionKeyManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateKeyWhenFileDoesNotExist() {
        Path keyFile = tempDir.resolve("new-key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(keyFile.toString());

        assertThat(manager.getEncryptionKey()).isNotBlank();
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void shouldLoadExistingKeyFromFile() throws IOException {
        Path keyFile = tempDir.resolve("existing-key.key");
        String expectedKey = "TestKey1234567890123456789012345678901234567890";
        Files.writeString(keyFile, expectedKey);

        EncryptionKeyManager manager = new EncryptionKeyManager(keyFile.toString());

        assertThat(manager.getEncryptionKey()).isEqualTo(expectedKey);
    }

    @Test
    void shouldPersistKeyToDisk() {
        Path keyFile = tempDir.resolve("persist-key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(keyFile.toString());
        String firstKey = manager.getEncryptionKey();

        // Create a new instance - should load the same key
        EncryptionKeyManager manager2 = new EncryptionKeyManager(keyFile.toString());
        String secondKey = manager2.getEncryptionKey();

        assertThat(firstKey).isEqualTo(secondKey);
    }

    @Test
    void shouldCreateParentDirectories() {
        Path keyFile = tempDir.resolve("subdir/nested/key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(keyFile.toString());

        assertThat(manager.getEncryptionKey()).isNotBlank();
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void shouldGenerateBase64EncodedKey() {
        Path keyFile = tempDir.resolve("base64-key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(keyFile.toString());
        String key = manager.getEncryptionKey();

        // Should be valid base64
        assertThat(key).matches("^[A-Za-z0-9+/]+=*$");
    }
}
