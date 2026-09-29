package com.kratisai.controlplane.git.credential;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SshKeyFile implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SshKeyFile.class);

    private final Path path;

    private SshKeyFile(Path path) {
        this.path = path;
    }

    public static SshKeyFile write(String pemContent) throws IOException {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        Path tempFile;
        try {
            tempFile = Files.createTempFile("kratis-ssh-", ".key", PosixFilePermissions.asFileAttribute(perms));
        } catch (UnsupportedOperationException e) {
            tempFile = Files.createTempFile("kratis-ssh-", ".key");
            if (!tempFile.toFile().setReadable(true, true)
                    || !tempFile.toFile().setWritable(false, false)
                    || !tempFile.toFile().setExecutable(false, false)) {
                throw new IllegalStateException("Failed to secure permissions of SSH key file " + tempFile, e);
            }
        }
        Files.writeString(tempFile, pemContent);
        return new SshKeyFile(tempFile);
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() {
        if (path == null) {
            return;
        }
        try {
            if (!path.toFile().canWrite()) {
                path.toFile().setWritable(true);
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Failed to delete SSH key file: {}", path, e);
        }
    }
}
