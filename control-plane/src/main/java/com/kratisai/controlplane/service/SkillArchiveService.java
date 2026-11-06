package com.kratisai.controlplane.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Component;

@Component
public class SkillArchiveService {

    private static final int MAX_ENTRIES = 5_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** Rejects absolute paths, traversal segments, and links that could escape the extraction root. */
    public void validate(byte[] archive) {
        int entries = 0;
        long totalBytes = 0;
        try (TarArchiveInputStream tar = open(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Skill archive contains too many entries");
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new IllegalArgumentException("Skill archive must not contain links");
                }
                validateEntryName(entry.getName());
                totalBytes += Math.max(0, entry.getSize());
                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Skill archive is too large");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid skill archive", e);
        }
        if (entries == 0) {
            throw new IllegalArgumentException("Skill archive is empty");
        }
    }

    public void extract(byte[] archive, Path targetDirectory) {
        validate(archive);
        Path root = targetDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            try (TarArchiveInputStream tar = open(archive)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    Path resolved = root.resolve(entry.getName()).normalize();
                    if (!resolved.startsWith(root)) {
                        throw new IllegalArgumentException(
                                "Skill archive entry escapes extraction root: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(resolved);
                    } else {
                        Path parent = resolved.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(tar, resolved);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract skill archive", e);
        }
    }

    public byte[] createArchive(Path skillPath) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            Path root = skillPath.toAbsolutePath().normalize();
            if (Files.isRegularFile(root)) {
                writeEntry(tar, root, SKILL_FILE_NAME);
            } else {
                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path path : paths.sorted().toList()) {
                        if (path.equals(root)) {
                            continue;
                        }
                        writeEntry(tar, path, root.relativize(path).toString().replace('\\', '/'));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create skill archive", e);
        }
        return bytes.toByteArray();
    }

    private static void writeEntry(TarArchiveOutputStream tar, Path path, String entryName) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
        entry.setMode(mode(path));
        tar.putArchiveEntry(entry);
        if (Files.isRegularFile(path)) {
            Files.copy(path, tar);
        }
        tar.closeArchiveEntry();
    }

    public String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static TarArchiveInputStream open(byte[] archive) throws IOException {
        return new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(archive)));
    }

    private static void validateEntryName(String name) {
        String normalizedName = name.replace('\\', '/');
        if (normalizedName.startsWith("/") || normalizedName.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Skill archive entry must be relative: " + name);
        }
        for (String segment : normalizedName.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Skill archive entry contains path traversal: " + name);
            }
        }
        Path normalized = Path.of(normalizedName).normalize();
        if (normalized.startsWith("..") || normalized.isAbsolute()) {
            throw new IllegalArgumentException("Skill archive entry escapes extraction root: " + name);
        }
    }

    private static int mode(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return 0755;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            return permissions.contains(PosixFilePermission.OWNER_EXECUTE) ? 0755 : 0644;
        } catch (UnsupportedOperationException e) {
            return 0644;
        }
    }
}
