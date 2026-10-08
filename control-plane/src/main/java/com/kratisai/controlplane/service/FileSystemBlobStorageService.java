package com.kratisai.controlplane.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "kratis.storage.type", havingValue = "local", matchIfMissing = true)
public class FileSystemBlobStorageService implements BlobStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemBlobStorageService.class);

    private final Path rootDirectory;

    @Autowired
    public FileSystemBlobStorageService(@Value("${kratis.storage.local-path:/data/blobs}") String rootPath) {
        this(Path.of(Objects.requireNonNull(rootPath, "rootPath must not be null")));
    }

    FileSystemBlobStorageService(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Fails startup when the configured blob root cannot accept writes. Diff patches are the only
     * copy of a sandbox diff surviving teardown, so a misconfigured or read-only store must surface
     * here — not as a dropped WebSocket event at runtime. Must stay {@link Lazy @Lazy(false)}: the
     * check is a constructor side effect, and under {@code spring.main.lazy-initialization=true}
     * (test contexts) a lazy validator never runs.
     */
    @Service
    @Lazy(false)
    public static class BlobStorageValidator {

        public BlobStorageValidator(FileSystemBlobStorageService storageService) {
            Path probe = Path.of(".startup-write-probe-" + UUID.randomUUID());
            try {
                storageService.putObject(probe.toString(), "ok".getBytes(StandardCharsets.UTF_8), "text/plain");
                storageService.deleteObject(probe.toString());
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Blob storage at " + storageService.rootDirectory + " is not writable", e);
            }
            logger.info("Blob storage validated at {}", storageService.rootDirectory);
        }
    }

    private Path resolveSafePath(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Path resolved = rootDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    @Override
    public void putObject(String path, byte[] content, String contentType) {
        Objects.requireNonNull(content, "content must not be null");
        Path target = resolveSafePath(path);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write object: " + path, e);
        }
    }

    @Override
    public void putObject(String path, InputStream content, long length, String contentType) {
        Objects.requireNonNull(content, "content must not be null");
        Path target = resolveSafePath(path);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stream object: " + path, e);
        }
    }

    @Override
    public byte[] getObjectBytes(String path) {
        Path target = resolveSafePath(path);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read object: " + path, e);
        }
    }

    @Override
    public InputStream getObject(String path) {
        Path target = resolveSafePath(path);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open stream for object: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        Path target = resolveSafePath(path);
        return Files.exists(target);
    }

    @Override
    public void deleteObject(String path) {
        Path target = resolveSafePath(path);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete object: " + path, e);
        }
    }
}
