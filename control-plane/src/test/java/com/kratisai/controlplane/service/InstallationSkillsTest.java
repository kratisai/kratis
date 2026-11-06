package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InstallationSkillsTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void shippedInstallationSkillsMatchTheStrictFrontmatterContract() throws Exception {
        Path directory = Path.of("skills");
        assertThat(directory).isDirectory();

        List<Path> files;
        try (Stream<Path> children = Files.list(directory)) {
            files = children.sorted().toList();
        }
        assertThat(files).isNotEmpty();
        for (Path child : files) {
            Path skillFile = Files.isDirectory(child) ? child.resolve("SKILL.md") : child;
            if (Files.isRegularFile(skillFile) && skillFile.toString().endsWith(".md")) {
                SkillFrontmatterParser.ParsedSkill parsed = parser.parseStrict(skillFile);
                assertThat(parsed.name()).isNotBlank();
                assertThat(parsed.description()).isNotBlank();
            }
        }
    }
}
