package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.repository.TeamSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillResolverServiceTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path directory;

    private Path installationDirectory;
    private TeamSkillRepository teamSkillRepository;
    private SkillResolverService resolver;

    @BeforeEach
    void setUp() {
        installationDirectory = directory.resolve("installation");
        teamSkillRepository = mock(TeamSkillRepository.class);
        InstanceProperties properties = new InstanceProperties();
        properties.setSkillsDirectory(installationDirectory.toString());
        resolver = new SkillResolverService(properties, teamSkillRepository, new SkillFrontmatterParser());
    }

    @Test
    void teamSkillOverridesInstallation() throws Exception {
        writeSkill(installationDirectory.resolve("deploy"), "deploy", "Installation deploy");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID))
                .thenReturn(List.of(teamSkill("deploy", "Team deploy")));

        assertThat(resolver.resolveCatalog(TEAM_ID)).singleElement().satisfies(skill -> {
            assertThat(skill.source()).isEqualTo(SkillSource.TEAM);
            assertThat(skill.description()).isEqualTo("Team deploy");
        });
    }

    @Test
    void disabledTeamSkillFallsBackToInstallation() throws Exception {
        writeSkill(installationDirectory.resolve("lint"), "lint", "Installation lint");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());

        assertThat(resolver.resolveCatalog(TEAM_ID)).singleElement().satisfies(skill -> {
            assertThat(skill.source()).isEqualTo(SkillSource.INSTALLATION);
            assertThat(skill.description()).isEqualTo("Installation lint");
        });
    }

    @Test
    void installationAndTeamSourcesAreAllReturnedSorted() throws Exception {
        writeSkill(installationDirectory.resolve("installation-skill"), "installation-skill", "Install");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID))
                .thenReturn(List.of(teamSkill("team-skill", "Team")));

        assertThat(resolver.resolveCatalog(TEAM_ID))
                .extracting(SkillDefinition::name)
                .containsExactly("installation-skill", "team-skill");
    }

    @Test
    void nullTeamResolvesInstallationSkillsWithoutTeamLookup() throws Exception {
        writeSkill(installationDirectory.resolve("lint"), "lint", "Installation lint");

        assertThat(resolver.resolveCatalog(null))
                .extracting(SkillDefinition::name)
                .containsExactly("lint");
        verifyNoInteractions(teamSkillRepository);
    }

    @Test
    void missingInstallationDirectoryIsEmpty() {
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());

        assertThat(resolver.resolveCatalog(TEAM_ID)).isEmpty();
    }

    @Test
    void malformedInstallationSkillIsSkipped() throws Exception {
        writeSkill(installationDirectory.resolve("valid"), "valid", "Valid");
        Path broken = Files.createDirectories(installationDirectory.resolve("broken"));
        Files.writeString(broken.resolve("SKILL.md"), "# no frontmatter");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());

        assertThat(resolver.resolveCatalog(TEAM_ID))
                .extracting(SkillDefinition::name)
                .containsExactly("valid");
    }

    @Test
    void installationDirectoryNameMismatchIsSkipped() throws Exception {
        writeSkill(installationDirectory.resolve("lint"), "other", "Mismatched");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());

        assertThat(resolver.resolveCatalog(TEAM_ID)).isEmpty();
    }

    @Test
    void installationFlatSkillIsResolvedToItsFile() throws Exception {
        Files.createDirectories(installationDirectory);
        Path flat = installationDirectory.resolve("deploy.md");
        Files.writeString(flat, "---\nname: deploy\ndescription: Deploy\n---\n");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());

        assertThat(resolver.resolveCatalog(TEAM_ID)).singleElement().satisfies(skill -> {
            assertThat(skill.source()).isEqualTo(SkillSource.INSTALLATION);
            assertThat(skill.path()).isEqualTo(flat);
        });
    }

    @Test
    void teamSkillWithInvalidMetadataIsSkipped() {
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID))
                .thenReturn(List.of(teamSkill("Bad_Name", "Team"), teamSkill("valid", "Team valid")));

        assertThat(resolver.resolveCatalog(TEAM_ID))
                .extracting(SkillDefinition::name)
                .containsExactly("valid");
    }

    private static void writeSkill(Path directory, String name, String description) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                # %s
                """.formatted(name, description, name));
    }

    private static TeamSkill teamSkill(String name, String description) {
        TeamSkill skill = new TeamSkill();
        skill.setName(name);
        skill.setDescription(description);
        skill.setBlobPath("skills/teams/" + TEAM_ID + "/" + name + ".tar.gz");
        skill.setChecksumSha256("a".repeat(64));
        skill.setEnabled(true);
        return skill;
    }
}
