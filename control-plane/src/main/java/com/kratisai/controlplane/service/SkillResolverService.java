package com.kratisai.controlplane.service;

import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.repository.TeamSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SkillResolverService {

    private static final Logger logger = LoggerFactory.getLogger(SkillResolverService.class);

    private final InstanceProperties instanceProperties;
    private final TeamSkillRepository teamSkillRepository;
    private final SkillFrontmatterParser frontmatterParser;

    public SkillResolverService(
            InstanceProperties instanceProperties,
            TeamSkillRepository teamSkillRepository,
            SkillFrontmatterParser frontmatterParser) {
        this.instanceProperties = instanceProperties;
        this.teamSkillRepository = teamSkillRepository;
        this.frontmatterParser = frontmatterParser;
    }

    /** Resolves platform-owned skills with Team &gt; Installation precedence. Repository skills are not included. */
    public List<SkillDefinition> resolveCatalog(UUID teamId) {
        Map<String, SkillDefinition> resolved = new LinkedHashMap<>();
        putAll(resolved, scanInstallationSkills());
        putAll(resolved, teamId != null ? loadTeamSkills(teamId) : List.of());
        return sorted(resolved);
    }

    private List<SkillDefinition> loadTeamSkills(UUID teamId) {
        return teamSkillRepository.findByTeamIdAndEnabledTrue(teamId).stream()
                .map(this::toDefinition)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<SkillDefinition> toDefinition(TeamSkill skill) {
        if (skill.getName() == null
                || skill.getDescription() == null
                || skill.getBlobPath() == null
                || skill.getBlobPath().isBlank()) {
            logger.warn("Skipping team skill {} with incomplete metadata", skill.getId());
            return Optional.empty();
        }
        try {
            return Optional.of(new SkillDefinition(
                    skill.getName(), skill.getDescription(), Path.of(skill.getBlobPath()), SkillSource.TEAM));
        } catch (IllegalArgumentException e) {
            logger.warn("Skipping team skill {} with invalid metadata: {}", skill.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private List<SkillDefinition> scanInstallationSkills() {
        String directory = instanceProperties.getSkillsDirectory();
        if (directory == null || directory.isBlank()) {
            return List.of();
        }
        return scanSkillDirectory(Path.of(directory));
    }

    private List<SkillDefinition> scanSkillDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();
        List<Path> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream.sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            logger.warn("Unable to scan skills directory {}: {}", directory, e.getMessage());
            return List.of();
        }
        for (Path child : children) {
            Path skillFile = Files.isDirectory(child) ? child.resolve("SKILL.md") : child;
            if (!Files.isRegularFile(skillFile) || !skillFile.toString().endsWith(".md")) {
                continue;
            }
            frontmatterParser
                    .parse(skillFile)
                    .ifPresent(metadata -> skills.put(
                            metadata.name(),
                            new SkillDefinition(
                                    metadata.name(), metadata.description(), child, SkillSource.INSTALLATION)));
        }
        return List.copyOf(skills.values());
    }

    private static void putAll(Map<String, SkillDefinition> target, List<SkillDefinition> skills) {
        for (SkillDefinition skill : skills) {
            target.put(skill.name(), skill);
        }
    }

    private static List<SkillDefinition> sorted(Map<String, SkillDefinition> skills) {
        return skills.values().stream()
                .sorted(Comparator.comparing(SkillDefinition::name))
                .toList();
    }
}
