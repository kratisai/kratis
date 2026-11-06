package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillFrontmatterParserTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void parsesNameAndDescription() {
        SkillFrontmatterParser.ParsedSkill parsed = parser.parseContent("""
                ---
                name: repo-db-migration
                description: Execute database migrations. Use when the task changes the schema.
                ---

                # Migration skill
                """);

        assertThat(parsed.name()).isEqualTo("repo-db-migration");
        assertThat(parsed.description())
                .isEqualTo("Execute database migrations. Use when the task changes the schema.");
    }

    @Test
    void ignoresUnknownAndSpecOptionalKeys() {
        SkillFrontmatterParser.ParsedSkill parsed = parser.parseContent("""
                ---
                name: deploy
                description: Deploy to staging
                trigger: Ignored
                license: Apache-2.0
                metadata:
                  author: example-org
                ---
                """);

        assertThat(parsed).isEqualTo(new SkillFrontmatterParser.ParsedSkill("deploy", "Deploy to staging"));
    }

    @Test
    void descriptionIsRequired() {
        assertThatThrownBy(() -> parser.parseContent("""
                        ---
                        name: lint
                        ---
                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a description");
    }

    @ParameterizedTest
    @ValueSource(strings = {"description: \"\"", "description: '   '"})
    void blankDescriptionIsRejected(String descriptionLine) {
        assertThatThrownBy(() -> parser.parseContent("---\nname: lint\n" + descriptionLine + "\n---\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a description");
    }

    @Test
    void descriptionAtLimitIsAccepted() {
        String description = "d".repeat(SkillDefinition.MAX_DESCRIPTION_LENGTH);

        assertThat(parser.parseContent("---\nname: lint\ndescription: " + description + "\n---\n")
                        .description())
                .hasSize(SkillDefinition.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void descriptionOverLimitIsRejected() {
        String description = "d".repeat(SkillDefinition.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> parser.parseContent("---\nname: lint\ndescription: " + description + "\n---\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1024 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lint", "pdf-processing", "a", "tool2-v3"})
    void specCompliantNamesAreAccepted(String name) {
        assertThat(parser.parseContent("---\nname: " + name + "\ndescription: Works\n---\n")
                        .name())
                .isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PDF-Processing",
                "pdf_processing",
                "pdf.processing",
                "-pdf",
                "pdf-",
                "pdf--processing",
                "../escape",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void nonSpecNamesAreRejected(String name) {
        assertThatThrownBy(() -> parser.parseContent("---\nname: \"" + name + "\"\ndescription: Bad\n---\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a name");
    }

    @Test
    void missingFrontmatterIsMalformed(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("SKILL.md");
        Files.writeString(file, "# No frontmatter");

        assertThat(parser.parse(file)).isEmpty();
    }

    @Test
    void missingDescriptionIsMalformed(@TempDir Path directory) throws Exception {
        Path file = Files.createDirectories(directory.resolve("lint")).resolve("SKILL.md");
        Files.writeString(file, "---\nname: lint\n---\n");

        assertThat(parser.parse(file)).isEmpty();
    }

    @Test
    void nameMustMatchParentDirectory(@TempDir Path directory) throws Exception {
        Path file = Files.createDirectories(directory.resolve("lint")).resolve("SKILL.md");
        Files.writeString(file, "---\nname: other\ndescription: Lint\n---\n");

        assertThat(parser.parse(file)).isEmpty();
        assertThatThrownBy(() -> parser.parseStrict(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match its location 'lint'");
    }

    @Test
    void flatFileNameMustMatchFileStem(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("lint.md");
        Files.writeString(file, "---\nname: other\ndescription: Lint\n---\n");

        assertThat(parser.parse(file)).isEmpty();
    }

    @Test
    void flatFileWithMatchingNameIsAccepted(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("lint.md");
        Files.writeString(file, "---\nname: lint\ndescription: Lint\n---\n");

        assertThat(parser.parseStrict(file)).isEqualTo(new SkillFrontmatterParser.ParsedSkill("lint", "Lint"));
    }

    @Test
    void explicitNullLocationSkipsNameCheck(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("SKILL.md");
        Files.writeString(file, "---\nname: lint\ndescription: Lint\n---\n");

        assertThat(parser.parseStrict(file, null).name()).isEqualTo("lint");
    }
}
