package com.kratisai.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SkillFrontmatterParser {

    private static final Logger logger = LoggerFactory.getLogger(SkillFrontmatterParser.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String FLAT_SKILL_SUFFIX = ".md";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public record ParsedSkill(String name, String description) {}

    public Optional<ParsedSkill> parse(Path skillFile) {
        try {
            return Optional.of(parseStrict(skillFile));
        } catch (RuntimeException e) {
            logger.warn("Skipping malformed skill file {}: {}", skillFile, e.getMessage());
            return Optional.empty();
        }
    }

    /** Validates the file against its location: a SKILL.md must sit in a directory named after the skill. */
    public ParsedSkill parseStrict(Path skillFile) {
        return parseStrict(skillFile, locationName(skillFile).orElse(null));
    }

    /** A {@code null} expectedName skips the location check, for archives whose root directory is a temporary path. */
    public ParsedSkill parseStrict(Path skillFile, String expectedName) {
        ParsedSkill parsed = parseContent(read(skillFile));
        if (expectedName != null && !parsed.name().equals(expectedName)) {
            throw new IllegalArgumentException(
                    "Skill name '" + parsed.name() + "' must match its location '" + expectedName + "'");
        }
        return parsed;
    }

    public ParsedSkill parseContent(String content) {
        Map<String, Object> values = readValues(extractFrontmatter(content));
        if (values == null) {
            throw new IllegalArgumentException("Skill frontmatter is empty");
        }

        String name = stringValue(values.get("name"));
        if (!SkillDefinition.isValidName(name)) {
            throw new IllegalArgumentException(
                    "Skill frontmatter requires a name of up to 64 lowercase letters, digits, and single hyphens");
        }
        String description = stringValue(values.get("description"));
        if (description == null) {
            throw new IllegalArgumentException("Skill frontmatter requires a description");
        }
        if (description.length() > SkillDefinition.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill description must be at most " + SkillDefinition.MAX_DESCRIPTION_LENGTH + " characters");
        }
        return new ParsedSkill(name, description);
    }

    private Map<String, Object> readValues(String frontmatter) {
        try {
            return yaml.readValue(frontmatter, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid YAML frontmatter", e);
        }
    }

    private static Optional<String> locationName(Path skillFile) {
        Optional<Path> fileName = Optional.ofNullable(skillFile.getFileName());
        if (fileName.isEmpty()) {
            return Optional.empty();
        }
        String name = fileName.get().toString();
        if (SKILL_FILE.equals(name)) {
            return Optional.ofNullable(skillFile.toAbsolutePath().getParent())
                    .map(Path::getFileName)
                    .map(Path::toString);
        }
        return Optional.of(
                name.endsWith(FLAT_SKILL_SUFFIX)
                        ? name.substring(0, name.length() - FLAT_SKILL_SUFFIX.length())
                        : name);
    }

    private static String read(Path skillFile) {
        try {
            return Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read skill file " + skillFile, e);
        }
    }

    private static String extractFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("Skill file must start with YAML frontmatter");
        }
        int closing = normalized.indexOf("\n---", 4);
        if (closing < 0) {
            throw new IllegalArgumentException("Skill frontmatter is not terminated");
        }
        return normalized.substring(4, closing + 1);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String rendered = String.valueOf(value).trim();
        return rendered.isEmpty() ? null : rendered;
    }
}
