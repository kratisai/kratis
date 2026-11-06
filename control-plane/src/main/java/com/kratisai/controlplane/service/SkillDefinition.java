package com.kratisai.controlplane.service;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public record SkillDefinition(String name, String description, Path path, SkillSource source) {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    public SkillDefinition {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(description, "description is required");
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(source, "source is required");
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid skill name: " + name);
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("Skill description is required");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
    }

    public static boolean isValidName(String name) {
        return name != null
                && name.length() <= MAX_NAME_LENGTH
                && NAME_PATTERN.matcher(name).matches();
    }

    public String catalogPath(String skillsDir) {
        String root = skillsDir.endsWith("/") ? skillsDir.substring(0, skillsDir.length() - 1) : skillsDir;
        return root + "/" + name + "/SKILL.md";
    }
}
