package com.kratisai.controlplane.git.credential;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;

class SshKeyFileTest {

    @Test
    void write_shouldCreateFileWithCorrectPermissions() throws Exception {
        try (SshKeyFile key = SshKeyFile.write("test-pem")) {
            assertThat(key.path()).exists();
            assertThat(Files.readString(key.path())).isEqualTo("test-pem");

            var attrs = Files.getPosixFilePermissions(key.path());
            assertThat(attrs).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void close_shouldDeleteFile() throws Exception {
        SshKeyFile key = SshKeyFile.write("test-pem");
        var path = key.path();
        key.close();
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void close_shouldDeleteFileOnExceptionInTryWithResources() throws Exception {
        SshKeyFile key = SshKeyFile.write("test-pem");
        try {
            try {
                throw new RuntimeException("forced exception");
            } finally {
                key.close();
            }
        } catch (RuntimeException e) {
            // expected
        }
        assertThat(Files.exists(key.path())).isFalse();
    }

    @Test
    void write_emptyContent_shouldStillCreateFile() throws Exception {
        try (SshKeyFile key = SshKeyFile.write("")) {
            assertThat(Files.readString(key.path())).isEmpty();
        }
    }
}
