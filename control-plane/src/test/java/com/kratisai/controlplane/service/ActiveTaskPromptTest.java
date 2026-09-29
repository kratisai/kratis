package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActiveTaskPromptTest {

    @Test
    void buildTaskPrompt_containsChaptersAndCanonicalPointer() {
        String prompt = ActiveTaskPrompt.buildTaskPrompt("# Plan\nDo stuff");

        assertThat(prompt).contains("You are running as a non-root user inside a sandbox that you own.");
        assertThat(prompt).contains("prefix the command with sudo");
        assertThat(prompt).contains("Approval prompts WILL be answered via HITL");
        assertThat(prompt).contains("Canonical task copy: " + ActiveTaskPrompt.ACTIVE_TASK_PATH);
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
        String wrapped = ActiveTaskPrompt.withTaskPointer("please continue");

        assertThat(wrapped).startsWith("Before acting, re-read the canonical task file fresh: ");
        assertThat(wrapped).contains(ActiveTaskPrompt.ACTIVE_TASK_PATH);
        assertThat(wrapped).contains("first gate whose exit is not");
        assertThat(wrapped).endsWith("please continue");
    }

    @Test
    void withTaskPointer_nullPrompt_toleratesNullBody() {
        assertThat(ActiveTaskPrompt.withTaskPointer(null)).contains(ActiveTaskPrompt.ACTIVE_TASK_PATH);
    }

    @Test
    void buildActiveTaskDocument_containsImmutableSectionsAndAgentScratch() {
        String document = ActiveTaskPrompt.buildActiveTaskDocument(UUID.randomUUID(), "# Plan\nDo stuff");

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
    void buildWriteCommands_targetsActiveTaskPathAndRoundTrips() {
        UUID executionId = UUID.randomUUID();
        String document = ActiveTaskPrompt.buildActiveTaskDocument(executionId, "# Plan\nDo stuff");

        List<String> commands = ActiveTaskPrompt.buildWriteCommands(document);

        assertThat(commands).isNotEmpty();
        assertThat(commands.getLast()).contains(ActiveTaskPrompt.ACTIVE_TASK_PATH);
        StringBuilder encoded = new StringBuilder();
        for (String command : commands.subList(0, commands.size() - 1)) {
            assertThat(command).contains(ActiveTaskPrompt.ACTIVE_TASK_PATH);
            int start = command.indexOf("echo '") + "echo '".length();
            int end = command.indexOf("' | base64 -d");
            encoded.append(command, start, end);
        }
        assertThat(new String(Base64.getDecoder().decode(encoded.toString()), StandardCharsets.UTF_8))
                .isEqualTo(document);
        assertThat(commands.getLast()).contains("git check-ignore -q '.kratis/ACTIVE_TASK.md'");
    }
}
