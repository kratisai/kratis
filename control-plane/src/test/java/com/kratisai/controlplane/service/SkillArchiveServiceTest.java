package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillArchiveServiceTest {

    private final SkillArchiveService archiveService = new SkillArchiveService();

    @Test
    void roundTripsSkillDirectory(@TempDir Path directory) throws Exception {
        Path skillRoot = Files.createDirectories(directory.resolve("lint"));
        Files.writeString(skillRoot.resolve("SKILL.md"), "skill");
        Path scripts = Files.createDirectories(skillRoot.resolve("scripts"));
        Files.writeString(scripts.resolve("run.sh"), "echo lint");

        byte[] archive = archiveService.createArchive(skillRoot);
        archiveService.validate(archive);

        Path extracted = directory.resolve("extracted");
        archiveService.extract(archive, extracted);

        assertThat(extracted.resolve("SKILL.md")).hasContent("skill");
        assertThat(extracted.resolve("scripts/run.sh")).hasContent("echo lint");
    }

    @Test
    void flatSkillFileIsArchivedAsSkillMd(@TempDir Path directory) throws Exception {
        Path flat = Files.writeString(directory.resolve("lint.md"), "skill");

        byte[] archive = archiveService.createArchive(flat);
        archiveService.validate(archive);

        Path extracted = directory.resolve("extracted");
        archiveService.extract(archive, extracted);

        assertThat(extracted.resolve("SKILL.md")).hasContent("skill");
    }

    @Test
    void rejectsPathTraversalEntries() {
        byte[] archive = tarArchive(new TarArchiveEntry("../../etc/passwd"), "root:x:0:0");

        assertThatThrownBy(() -> archiveService.validate(archive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void rejectsAbsolutePaths() {
        byte[] archive = tarArchive(new TarArchiveEntry("C:/etc/passwd"), "root:x:0:0");

        assertThatThrownBy(() -> archiveService.validate(archive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
    }

    @Test
    void rejectsSymbolicLinks() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry link = new TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("/etc/passwd");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }

        assertThatThrownBy(() -> archiveService.validate(bytes.toByteArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("links");
    }

    @Test
    void sha256IsStable() {
        byte[] content = "skill".getBytes(StandardCharsets.UTF_8);

        assertThat(archiveService.sha256(content))
                .isEqualTo(archiveService.sha256(content))
                .matches("[0-9a-f]{64}");
    }

    private static byte[] tarArchive(TarArchiveEntry entry, String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                    TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
                byte[] payload = content.getBytes(StandardCharsets.UTF_8);
                entry.setSize(payload.length);
                tar.putArchiveEntry(entry);
                tar.write(payload);
                tar.closeArchiveEntry();
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
