package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveTaskPromptTest {

    @TempDir
    Path directory;

    private String workspace() {
        return directory.resolve("workspace").toString();
    }

    private String activeTaskPath() {
        return workspace() + "/.kratis/ACTIVE_TASK.md";
    }

    private String platformDir() {
        return workspace() + "/.kratis";
    }

    private String skillsDir() {
        return platformDir() + "/skills";
    }

    @Test
    void buildTaskPrompt_containsChaptersAndCanonicalPointer() {
        String prompt = ActiveTaskPrompt.buildTaskPrompt("# Plan\nDo stuff", activeTaskPath());

        assertThat(prompt).contains("You are running as a non-root user inside a sandbox that you own.");
        assertThat(prompt).contains("prefix the command with sudo");
        assertThat(prompt).contains("Approval prompts WILL be answered via HITL");
        assertThat(prompt).contains("Canonical task copy: " + activeTaskPath());
        assertThat(prompt).contains("Keep command output bounded");
        assertThat(prompt).contains("Execute the user's task systematically: prepare, execute and verify.");
        assertThat(prompt).contains("First, Prepare the environment.");
        assertThat(prompt).contains("Inspect `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`");
        assertThat(prompt).contains("install libraries and dependencies as needed");
        assertThat(prompt).contains("Next execute the task.");
        assertThat(prompt).contains("extract the Definition of Done");
        assertThat(prompt).contains("verify-as-you-go");
        assertThat(prompt).contains("Ensure test-coverage and run relevant tests and checks.");
        assertThat(prompt).contains("Finally verify completeness and quality.");
        assertThat(prompt).contains("Re-read the task copy and ensure that all steps have been completed.");
        assertThat(prompt).contains("Execute a strict Definition Of Done as defined by the repository.");
        assertThat(prompt).contains("### User Task follows - use the above structure to fully deliver this task");
        assertThat(prompt).contains("=====================================");
        assertThat(prompt).contains("# Plan\nDo stuff");
        // The simplified prompt no longer wraps the canvas in plan_context markers.
        assertThat(prompt).doesNotContain("<plan_context>");
    }

    @Test
    void withTaskPointer_prefixesCanonicalFileAndGateResume() {
        String wrapped = ActiveTaskPrompt.withTaskPointer("please continue", activeTaskPath());

        assertThat(wrapped).startsWith("Before acting, re-read the canonical task file fresh: ");
        assertThat(wrapped).contains(activeTaskPath());
        assertThat(wrapped).contains("first gate whose exit is not");
        assertThat(wrapped).endsWith("please continue");
    }

    @Test
    void withTaskPointer_nullPrompt_toleratesNullBody() {
        assertThat(ActiveTaskPrompt.withTaskPointer(null, activeTaskPath())).contains(activeTaskPath());
    }

    @Test
    void buildActiveTaskDocument_containsImmutableSectionsAndAgentScratch() {
        String document = ActiveTaskPrompt.buildActiveTaskDocument("# Plan\nDo stuff", List.of(), skillsDir());

        assertThat(document).contains("## USER TASK (platform, immutable)");
        assertThat(document).contains("# Plan\nDo stuff");
        assertThat(document).contains("## DOCKER (platform, immutable)");
        assertThat(document).contains("$TESTCONTAINERS_HOST_OVERRIDE");
        assertThat(document).contains("## OUTPUT (platform, immutable)");
        assertThat(document).contains("Keep command output bounded");
        assertThat(document).contains("## Agent scratch (agent owns below this line)");
        assertThat(document).contains("### Plan");
        assertThat(document).contains("- [ ] step — exit: <command + expected>");
        assertThat(document).contains("### DoD (from canvas + repo docs)");
        assertThat(document).contains("### Evidence log");
    }

    @Test
    void buildActiveTaskDocument_rendersAvailableSkillsTable() {
        List<SkillDefinition> skills = List.of(
                new SkillDefinition(
                        "sandbox-service-export",
                        "Expose a local network port for user verification",
                        Path.of("skills/teams/x/sandbox-service-export.tar.gz"),
                        SkillSource.TEAM),
                new SkillDefinition(
                        "repo-db-migration",
                        "Execute database migrations using existing project Liquibase scripts",
                        Path.of("skills/teams/x/repo-db-migration.tar.gz"),
                        SkillSource.TEAM));

        String document = ActiveTaskPrompt.buildActiveTaskDocument("# Canvas", skills, skillsDir());

        assertThat(document).contains("## Available Skills");
        assertThat(document).contains("| Skill Name | Path | When to Use |");
        assertThat(document)
                .contains("| sandbox-service-export | `" + skillsDir() + "/sandbox-service-export/SKILL.md` | "
                        + "Expose a local network port for user verification |");
        assertThat(document)
                .contains("| repo-db-migration | `" + skillsDir() + "/repo-db-migration/SKILL.md` | "
                        + "Execute database migrations using existing project Liquibase scripts |");
        assertThat(document).contains("Read the referenced `SKILL.md` file before performing a task");
        assertThat(document).contains("# Canvas");
    }

    @Test
    void availableSkillsSectionDirectsAgentToDiscoverRepositorySkills() {
        String section = ActiveTaskPrompt.buildAvailableSkillsSection(List.of(), skillsDir());

        assertThat(section)
                .contains("Repository skills checked into this repository are not listed above")
                .contains("Perform your own discovery of repository-skills")
                .contains("Repository skills take precedence over the above skills.");
    }

    @Test
    void buildAvailableSkillsSection_emptyUsesNoneRow() {
        assertThat(ActiveTaskPrompt.buildAvailableSkillsSection(List.of(), skillsDir()))
                .contains("| _None_ | | |");
    }

    @Test
    void buildWriteCommands_targetsActiveTaskPathAndRoundTrips() {
        String document = ActiveTaskPrompt.buildActiveTaskDocument("# Plan\nDo stuff", List.of(), skillsDir());

        List<String> commands = ActiveTaskPrompt.buildWriteCommands(document, activeTaskPath(), platformDir());

        assertThat(commands).isNotEmpty();
        assertThat(commands.getLast()).contains(activeTaskPath());
        StringBuilder encoded = new StringBuilder();
        for (String command : commands.subList(0, commands.size() - 1)) {
            assertThat(command).contains(activeTaskPath());
            int start = command.indexOf("echo '") + "echo '".length();
            int end = command.indexOf("' | base64 -d");
            encoded.append(command, start, end);
        }
        assertThat(new String(Base64.getDecoder().decode(encoded.toString()), StandardCharsets.UTF_8))
                .isEqualTo(document);
        assertThat(commands.getLast()).contains("git check-ignore -q '.kratis/ACTIVE_TASK.md'");
    }
}
