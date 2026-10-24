package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.config.SecurityProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptionKeyManagerTest {

    @TempDir
    Path tempDir;

    private static SecurityProperties securityProperties(Path keyFile) {
        SecurityProperties properties = new SecurityProperties();
        properties.setKeyFilePath(keyFile.toString());
        return properties;
    }

    @Test
    void concurrentManagersOnSameKeyFileShareOneKey() throws Exception {
        Path keyFile = tempDir.resolve("concurrent-key.key");
        SecurityProperties properties = securityProperties(keyFile);
        int managers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(managers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> keys = new ArrayList<>();
            for (int i = 0; i < managers; i++) {
                keys.add(executor.submit(() -> {
                    start.await();
                    return new EncryptionKeyManager(properties).getEncryptionKey();
                }));
            }
            start.countDown();

            Set<String> distinctKeys = new HashSet<>();
            for (Future<String> key : keys) {
                distinctKeys.add(key.get(30, TimeUnit.SECONDS));
            }

            assertThat(distinctKeys).hasSize(1);
            assertThat(Files.readString(keyFile))
                    .isEqualTo(distinctKeys.iterator().next());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldGenerateKeyWhenFileDoesNotExist() {
        Path keyFile = tempDir.resolve("new-key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(securityProperties(keyFile));

        assertThat(manager.getEncryptionKey()).isNotBlank();
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void shouldLoadExistingKeyFromFile() throws IOException {
        Path keyFile = tempDir.resolve("existing-key.key");
        String expectedKey = "TestKey1234567890123456789012345678901234567890";
        Files.writeString(keyFile, expectedKey);

        EncryptionKeyManager manager = new EncryptionKeyManager(securityProperties(keyFile));

        assertThat(manager.getEncryptionKey()).isEqualTo(expectedKey);
    }

    @Test
    void shouldPersistKeyToDisk() {
        Path keyFile = tempDir.resolve("persist-key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(securityProperties(keyFile));
        String firstKey = manager.getEncryptionKey();

        // Create a new instance - should load the same key
        EncryptionKeyManager manager2 = new EncryptionKeyManager(securityProperties(keyFile));
        String secondKey = manager2.getEncryptionKey();

        assertThat(firstKey).isEqualTo(secondKey);
    }

    @Test
    void shouldCreateParentDirectories() {
        Path keyFile = tempDir.resolve("subdir/nested/key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(securityProperties(keyFile));

        assertThat(manager.getEncryptionKey()).isNotBlank();
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void shouldGenerateBase64EncodedKey() {
        Path keyFile = tempDir.resolve("base64-key.key");

        EncryptionKeyManager manager = new EncryptionKeyManager(securityProperties(keyFile));
        String key = manager.getEncryptionKey();

        // Should be valid base64
        assertThat(key).matches("^[A-Za-z0-9+/]+=*$");
    }
}
