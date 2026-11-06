package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillDefinitionTest {

    @TempDir
    Path directory;

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> new SkillDefinition("lint", " ", Path.of("lint"), SkillSource.TEAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongDescription() {
        String description = "d".repeat(SkillDefinition.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> new SkillDefinition("lint", description, Path.of("lint"), SkillSource.TEAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Lint",
                "lint_tool",
                "-lint",
                "lint-",
                "lint--tool",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void rejectsNonSpecNames(String name) {
        assertThat(SkillDefinition.isValidName(name)).isFalse();
        assertThatThrownBy(() -> new SkillDefinition(name, "Lint", Path.of("lint"), SkillSource.TEAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void teamCatalogPathIsSandboxSkillFile() {
        SkillDefinition skill =
                new SkillDefinition("lint", "Run lint", Path.of("skills/teams/t/lint.tar.gz"), SkillSource.TEAM);

        String skillsDir = directory.resolve("workspace/.kratis/skills").toString();

        assertThat(skill.catalogPath(skillsDir)).isEqualTo(skillsDir + "/lint/SKILL.md");
    }
}
