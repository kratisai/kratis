package com.kratisai.controlplane.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class ActiveTaskPrompt {

    public static final String ACTIVE_TASK_PATH = "/kratis/workspace/.kratis/ACTIVE_TASK.md";
    private static final String ACTIVE_TASK_DIR = "/kratis/workspace/.kratis";

    private static final int WRITE_CHUNK_CHARS = 8000;

    private static final String DOCKER_GUIDANCE = """
            Docker talks to a rootless Docker-in-Docker sibling over TCP (`DOCKER_HOST`; there is no \
            socket) and Testcontainers is preconfigured.

            - Reach inner containers by their published port on `$TESTCONTAINERS_HOST_OVERRIDE`, \
            never by container IP: container IPs are not routable from here.
            - `--privileged` inner containers fail to start; do not reach for it.
            - Ryuk is disabled and container reuse is enabled, so containers persist across runs. \
            Remove the reusable container if state goes stale.
            """;

    // A single unbounded command result (dependency downloads, full build logs) can exceed the model
    // context window and abort the run, so keep output small and inspect files by range.
    private static final String OUTPUT_GUIDANCE = """
            Keep command output bounded. Redirect noisy build/test/install logs to a file and read \
            only the tail (e.g. `./mvnw -q verify > /tmp/verify.log 2>&1; tail -n 120 /tmp/verify.log`); \
            never `cat` a whole large file or log, and read only the relevant line range instead. \
            A single multi-megabyte command result can exceed the model context window and abort the run.
            """;

    private ActiveTaskPrompt() {}

    public static String withTaskPointer(String promptText) {
        String body = promptText != null ? promptText : "";
        return """
                Before acting, re-read the canonical task file fresh: %s (§1 Task plus §3 Gates) and \
                continue from the first gate whose exit is not yet evidenced in-session. Do not claim \
                completion without that evidence.

                %s""".formatted(ACTIVE_TASK_PATH, body);
    }

    public static String buildTaskPrompt(String canvasContent) {
        //                Execute the user's task systematically: prepare, execute and verify.
        //                First, Prepare the environment.
        //                 - Inspect `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md` (repo root first, then the task
        //                 subproject) and any repo `skills` for build/setup instructions
        String canvas = canvasContent != null ? canvasContent : "";
        return """
                You are running as a non-root user inside a sandbox that you own. You can and should \
                make changes to this sandbox as needed to complete the task. To install system \
                packages, prefix the command with sudo (e.g. `sudo apt update && sudo apt install -y \
                <package>`). Approval prompts WILL be answered via HITL — always attempt the action, \
                never skip work to avoid a prompt.

                Canonical task copy: %s — re-read it at every gate.

                %s

                Execute the user's task systematically: prepare, execute and verify.
                First, Prepare the environment.
                 - Inspect `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md` (repo root first, then the task
                 subproject) and any repo `skills` for build/setup instructions
                 - Identify the required tool chains and install system packages using 'sudo apt \
                 update && sudo apt install -y <package>'
                 - install libraries and dependencies as needed (`npm ci` or `sudo apt install -y` or \
                 `pip install` tec.  Use command appropriate for the repository or submodule)

                Next execute the task.
                 - Plan. Before edits: extract the Definition of Done (canvas acceptance plus \
                the repo-doc checks: build/test/lint/typecheck etc.)
                 - Implement the task as described in the task copy and verify-as-you-go.
                 - Ensure test-coverage and run relevant tests and checks.

                Finally verify completeness and quality.
                 - Re-read the task copy and ensure that all steps have been completed.
                 - Inspect `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md` (repo root first, then the task \
                subproject) and any repo `skills` and ensure that all guidance has been met.
                 - Execute a strict Definition Of Done as defined by the repository. Validate all \
                quality gates.

                ### User Task follows - use the above structure to fully deliver this task
                =====================================
                %s
                """.formatted(ACTIVE_TASK_PATH, OUTPUT_GUIDANCE, canvas);
    }

    public static String buildActiveTaskDocument(UUID executionId, String canvasContent) {
        String canvas = canvasContent != null ? canvasContent : "";
        return """
                > Source of truth. Re-read fresh at each gate below.
                > PLATFORM sections: do not edit. AGENT section: you own.

                ## USER TASK (platform, immutable)

                %s

                ## DOCKER (platform, immutable)

                %s

                ## OUTPUT (platform, immutable)

                %s

                ## Agent scratch (agent owns below this line)

                ### Plan

                - [ ] step — exit: <command + expected>

                ### DoD (from canvas + repo docs)

                - [ ] <check> — evidence:

                ### Evidence log
                """.formatted(canvas, DOCKER_GUIDANCE, OUTPUT_GUIDANCE);
    }

    public static List<String> buildWriteCommands(String document) {
        String content = document != null ? document : "";
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        List<String> commands = new ArrayList<>();
        String quotedPath = "'" + ACTIVE_TASK_PATH + "'";
        String quotedDir = "'" + ACTIVE_TASK_DIR + "'";
        if (encoded.isEmpty()) {
            commands.add("mkdir -p " + quotedDir + " && test -s " + quotedPath);
            return List.copyOf(commands);
        }
        boolean first = true;
        for (int i = 0; i < encoded.length(); i += WRITE_CHUNK_CHARS) {
            String chunk = encoded.substring(i, Math.min(i + WRITE_CHUNK_CHARS, encoded.length()));
            String redirect = first ? ">" : ">>";
            commands.add(
                    "mkdir -p " + quotedDir + " && echo '" + chunk + "' | base64 -d " + redirect + " " + quotedPath);
            first = false;
        }
        commands.add("printf '%s\\n' '.kratis/' >> .git/info/exclude 2>/dev/null || true; "
                + "git check-ignore -q '.kratis/ACTIVE_TASK.md' 2>/dev/null || true; test -s " + quotedPath);
        return List.copyOf(commands);
    }
}
