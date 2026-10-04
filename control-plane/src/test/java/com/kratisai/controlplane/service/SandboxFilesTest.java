package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxFilesTest {

    @Test
    void writeCommands_streamBase64ThatRoundTripsToTheContent() {
        String content = "# patch\n" + "a".repeat(5000);

        List<String> commands = SandboxFiles.writeCommands("/tmp/patch.mjs", content);

        StringBuilder encoded = new StringBuilder();
        for (String command : commands) {
            assertThat(command).contains("base64 -d").contains("/tmp/patch.mjs");
            int start = command.indexOf("echo '") + "echo '".length();
            int end = command.indexOf("' | base64 -d");
            encoded.append(command, start, end);
        }
        assertThat(new String(Base64.getDecoder().decode(encoded.toString()), StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    void writeCommands_chunksLargeContentWithAppendRedirects() {
        String content = "b".repeat(20000);

        List<String> commands = SandboxFiles.writeCommands("/tmp/patch.mjs", content);

        assertThat(commands).hasSize(4);
        assertThat(commands.getFirst()).contains("base64 -d > /tmp/patch.mjs");
        assertThat(commands.subList(1, commands.size()))
                .allSatisfy(command -> assertThat(command).contains("base64 -d >> /tmp/patch.mjs"));
    }

    @Test
    void writeCommands_emptyContentCreatesAnEmptyFile() {
        assertThat(SandboxFiles.writeCommands("/tmp/patch.mjs", "")).containsExactly(": > /tmp/patch.mjs");
    }

    @Test
    void readHarnessFile_readsFilesRelativeToTheHarnessDirectory(@TempDir Path dir) throws IOException {
        Path assets = Files.createDirectories(dir.resolve("gemini"));
        Files.writeString(assets.resolve("patch.mjs"), "patched");

        assertThat(SandboxFiles.readHarnessFile(dir, "gemini/patch.mjs")).isEqualTo("patched");
    }

    @Test
    void readHarnessFile_missingResourceFailsLoudly(@TempDir Path dir) {
        assertThatThrownBy(() -> SandboxFiles.readHarnessFile(dir, "gemini/does-not-exist.mjs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing harness resource");
    }

    @Test
    void readHarnessFile_rejectsPathsOutsideTheHarnessDirectory(@TempDir Path dir) {
        assertThatThrownBy(() -> SandboxFiles.readHarnessFile(dir, "../escape.mjs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escapes the harness directory");
    }
}
