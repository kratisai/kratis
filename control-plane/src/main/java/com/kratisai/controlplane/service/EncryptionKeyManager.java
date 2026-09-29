package com.kratisai.controlplane.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manages the encryption key used for credential encryption. Generates a random key at startup if
 * one doesn't exist and persists it to disk.
 */
@Component
public class EncryptionKeyManager {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionKeyManager.class);
    private static final int KEY_LENGTH_BYTES = 32; // 256 bits

    private final Path keyFilePath;
    private final String encryptionKey;

    public EncryptionKeyManager(@Value("${kratis.security.key-file-path}") String keyFilePath) {
        this.keyFilePath = Paths.get(keyFilePath).toAbsolutePath();
        this.encryptionKey = loadOrGenerateKey();
    }

    private String loadOrGenerateKey() {
        try {
            // Ensure parent directory exists
            Path parentDir = keyFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                logger.info("Created encryption key directory: {}", parentDir);
            }

            // Check if key file exists
            if (Files.exists(keyFilePath)) {
                String existingKey =
                        Files.readString(keyFilePath, StandardCharsets.UTF_8).trim();
                if (!existingKey.isEmpty()) {
                    logger.info("Loaded existing encryption key from: {}", keyFilePath);
                    return existingKey;
                } else {
                    logger.warn(
                            "Encryption key file exists but is empty at: {}. Generating a new key. Previously encrypted secrets will be unreadable.",
                            keyFilePath);
                }
            } else {
                logger.warn(
                        "No encryption key file found at: {}. Generating a new key. If you had previously encrypted secrets, they will be unreadable.",
                        keyFilePath);
            }

            // Generate new key
            String newKey = generateRandomKey();
            Files.writeString(keyFilePath, newKey, StandardCharsets.UTF_8);
            setRestrictiveFilePermissions(keyFilePath);
            logger.info("Generated and persisted new encryption key to: {}", keyFilePath);
            return newKey;

        } catch (IOException e) {
            throw new IllegalStateException("Failed to load or generate encryption key at path: " + keyFilePath, e);
        }
    }

    private String generateRandomKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] keyBytes = new byte[KEY_LENGTH_BYTES];
        secureRandom.nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    private void setRestrictiveFilePermissions(Path filePath) {
        try {
            Set<PosixFilePermission> perms = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(filePath, perms);
        } catch (UnsupportedOperationException e) {
            // File system doesn't support POSIX permissions (e.g., Windows) — acceptable fallback
            logger.debug("POSIX file permissions not supported on this file system");
        } catch (IOException e) {
            logger.warn("Failed to set restrictive file permissions on: {}", filePath, e);
        }
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }
}
