package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentHarnessTest {

    private static final String OPENCODE_BASH_TIMEOUT_ENV = "OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> DISABLED_HARNESS_IDS = List.of("AIDER", "OPENHANDS");

    @Test
    void setupCommands_doNotDumpGeneratedFilesToStdout() {
        for (AgentHarness harness : AgentHarness.values()) {
            assertThat(harness.getSetupCommands())
                    .as("%s must not cat generated config/wrapper files into execution logs", harness.name())
                    .noneMatch(command -> command.contains(" && cat "));
        }
    }

    @Test
    void openCodeSetupCommands_raiseBashToolDefaultTimeoutAboveBuiltInDefault() {
        List<String> commands = AgentHarness.valueOf("OPENCODE").getSetupCommands();

        String timeoutCommand = commands.stream()
                .filter(command -> command.contains(OPENCODE_BASH_TIMEOUT_ENV))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("OPENCODE setup commands must configure " + OPENCODE_BASH_TIMEOUT_ENV));

        long timeoutMs = Long.parseLong(
                timeoutCommand.substring(timeoutCommand.indexOf('=') + 1).trim());

        // OpenCode's built-in shell tool default is 120000 ms; that kills full build/test commands.
        assertThat(timeoutMs).isGreaterThan(120_000L);
    }

    @Test
    void openCodeSetupCommands_askPermissionForEditAndBash() throws Exception {
        JsonNode config = openCodeConfig();

        // Without ask, OpenCode auto-applies edits and shell and never raises HITL.
        assertThat(config.at("/permission/edit").asText()).isEqualTo("ask");
        assertThat(config.at("/permission/bash").asText()).isEqualTo("ask");
    }

    @Test
    void aiderSetupCommands_setOpenAiApiBaseForCustomEndpoints() {
        // Aider reads OPENAI_API_BASE, not OPENAI_BASE_URL, for OpenAI-compatible proxies.
        assertThat(AgentHarness.valueOf("AIDER").getSetupCommands())
                .anySatisfy(command -> assertThat(command).isEqualTo("export OPENAI_API_BASE=${LLM_BASE_URL}/v1"));
    }

    @Test
    void aiderSetupCommands_enableLocalLiteLlmCostMap() {
        // Without this, LiteLLM inside aider can treat Google-named models as Vertex and skip the proxy.
        assertThat(AgentHarness.valueOf("AIDER").getSetupCommands())
                .anySatisfy(command -> assertThat(command).isEqualTo("export LITELLM_LOCAL_MODEL_COST_MAP=True"));
    }

    @Test
    void aiderAgentCommand_prependsWrapperThatRewritesHardcodedModel() {
        // aider-acp hardcodes the model (src/acp-agent.ts: "gemini/gemini-2.5-flash") and never
        // passes --yes. Without the wrapper, aider calls generativelanguage.googleapis.com
        // (API_KEY_INVALID) and blocks on "Add .aider* to .gitignore?"; both observed in
        // AiderExecutionRealTest with the wrapper removed. The wrapper rewrites --model to
        // ${AIDER_MODEL} and appends --yes.
        assertThat(AgentHarness.valueOf("AIDER").getAgentCommand()).startsWith("PATH=$HOME/aider-wrapper:$PATH ");
        assertThat(AgentHarness.valueOf("AIDER").getSetupCommands())
                .anySatisfy(command -> assertThat(command).contains("args[$((i+1))]=\"__MODEL__\""));
        assertThat(AgentHarness.valueOf("AIDER").getSetupCommands())
                .anySatisfy(command -> assertThat(command).contains("exec \"__AIDER__\" --yes"));
    }

    @Test
    void codexSetupCommands_forceAgentFullAccess() {
        // codex-acp passes the session mode's approvalPolicy/sandboxPolicy on every turn, overriding
        // config.toml. Its only danger-full-access mode is agent-full-access; the approval-bearing
        // modes sandbox with bwrap, which cannot create a user namespace inside the Kratis runner
        // ("bwrap: No permissions to create a new namespace", verified by CodexExecutionRealTest).
        // This disables Codex-side approvals, so Kratis cannot gate Codex tool calls via HITL.
        assertThat(AgentHarness.valueOf("CODEX").getSetupCommands())
                .anySatisfy(command -> assertThat(command).isEqualTo("export INITIAL_AGENT_MODE=agent-full-access"));
        assertThat(AgentHarness.valueOf("CODEX").getSetupCommands())
                .noneMatch(command -> command.contains("CODEX_CONFIG"));
    }

    @Test
    void geminiSetupCommands_authenticateWithGeminiApiKeyEnvOnly() throws Exception {
        List<String> commands = AgentHarness.valueOf("GEMINI").getSetupCommands();

        // getAuthTypeFromEnv selects gemini-api-key from GEMINI_API_KEY; GOOGLE_API_KEY and
        // GOOGLE_CLOUD_PROJECT are only read for vertex-ai auth. The settings apiKey is not read by
        // validateAuthMethod, so the env var is the single source. Verified end-to-end by
        // GeminiExecutionRealTest with the other three removed.
        assertThat(commands)
                .anySatisfy(command -> assertThat(command).isEqualTo("export GEMINI_API_KEY=${VIRTUAL_KEY}"));
        assertThat(commands).noneMatch(command -> command.contains("GOOGLE_API_KEY"));
        assertThat(commands).noneMatch(command -> command.contains("GOOGLE_CLOUD_PROJECT"));
        assertThat(geminiSettings().has("apiKey"))
                .as("GEMINI settings.json must not duplicate the virtual key in apiKey")
                .isFalse();
    }

    @Test
    void geminiSetupCommands_writeValidSettingsDisablingInteractiveShell() throws Exception {
        JsonNode settings = geminiSettings();

        // gemini-cli >= 0.61 defaults the PTY shell on; ACP then drops AnsiOutput, so command
        // output never reaches tool_call_update.
        assertThat(settings.at("/tools/shell/enableInteractiveShell").asBoolean(true))
                .as(
                        "GEMINI settings.json must disable the interactive (PTY) shell so ACP tool_call_update carries command output")
                .isFalse();
        assertThat(settings.at("/security/auth/selectedType").asText()).isEqualTo("gemini-api-key");
    }

    @Test
    void geminiSetupCommands_disableExperimentalContextManagement() throws Exception {
        JsonNode settings = geminiSettings();

        // experimental.contextManagement selects generalistProfile, which drops the task-delivery
        // turn after the first model response so the agent greets and stops.
        assertThat(settings.at("/experimental/contextManagement").asBoolean(false))
                .as("GEMINI must not enable experimental.contextManagement")
                .isFalse();
        assertThat(settings.has("contextManagement"))
                .as("GEMINI must not ship contextManagement settings for the disabled feature")
                .isFalse();
    }

    @Test
    void geminiSetupCommands_trustWorkspaceSoGitAndHooksAreNotBlocked() {
        // CLI-side folder-trust defaults on (core defaults off); untrusted workspaces force a HITL
        // prompt on every git command and disable hooks/skills/memory.
        assertThat(AgentHarness.valueOf("GEMINI").getSetupCommands())
                .anySatisfy(command -> assertThat(command).isEqualTo("export GEMINI_CLI_TRUST_WORKSPACE=true"));
    }

    @Test
    void geminiSetupCommands_enableTelemetryOutfileWithoutPromptsOrUsageStats() throws Exception {
        JsonNode settings = geminiSettings();

        // chat.content_retry* in the outfile is the only live record of a silent InvalidStream abort.
        assertThat(settings.at("/telemetry/enabled").asBoolean(false)).isTrue();
        assertThat(settings.at("/telemetry/outfile").asText())
                .isEqualTo("/kratis/workspace/.kratis/gemini-telemetry.jsonl");
        assertThat(settings.at("/telemetry/logPrompts").asBoolean(true)).isFalse();
        assertThat(settings.at("/privacy/usageStatisticsEnabled").asBoolean(true))
                .isFalse();
    }

    @Test
    void geminiAgentLogFiles_matchDebugLogFileWithoutEnvFileDeclarations() {
        List<String> commands = AgentHarness.valueOf("GEMINI").getSetupCommands();

        // gemini-cli buffers console until exit, so the debug log is the live
        // diagnostic surface; the connector receives the paths via the launch
        // request, not the persisted env file.
        assertThat(AgentHarness.valueOf("GEMINI").getAgentLogFiles())
                .containsExactly("/kratis/workspace/.kratis/gemini-debug.log");
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .isEqualTo("export GEMINI_DEBUG_LOG_FILE=/kratis/workspace/.kratis/gemini-debug.log"));
        assertThat(commands).noneMatch(command -> command.contains("KRATIS_AGENT_"));
    }

    @Test
    void harnessAgentLogFiles_defaultToEmptyAndRejectBlankPaths() {
        assertThat(AgentHarness.valueOf("CODEX").getAgentLogFiles()).isEmpty();
        assertThat(AgentHarness.values()).allSatisfy(harness -> assertThat(harness.getAgentLogFiles())
                .allSatisfy(path -> assertThat(path).isNotBlank()));
    }

    @Test
    void geminiSetupCommands_pinValidatedGeminiCliVersion() {
        // The ACP tool-output patch rewrites version-specific bundle code and fails if the anchor moved.
        assertThat(AgentHarness.valueOf("GEMINI").getSetupCommands()).anySatisfy(command -> assertThat(command)
                .isEqualTo("npm install --prefix $HOME/gemini @google/gemini-cli@0.61.0"));
    }

    @Test
    void geminiSetupCommands_runBundledAcpPatchAfterInstall() {
        List<String> commands = AgentHarness.valueOf("GEMINI").getSetupCommands();

        int installIndex = indexOfContaining(commands, "npm install --prefix $HOME/gemini");
        int patchIndex = indexOfContaining(commands, "node /tmp/kratis-gemini-acp-truncation.mjs");

        // ACP runner bypasses tools.truncateToolOutputThreshold (gemini-cli#27738); patch the bundle
        // after the install that produces it.
        assertThat(installIndex).as("GEMINI must install gemini-cli").isGreaterThanOrEqualTo(0);
        assertThat(patchIndex).as("GEMINI must run the ACP tool-output patch").isGreaterThan(installIndex);
        assertThat(commands.getLast())
                .isEqualTo("node /tmp/kratis-gemini-acp-truncation.mjs $HOME/gemini/node_modules/@google/gemini-cli");
    }

    @Test
    void geminiHarness_copiesAcpPatchResourceIntoTheSandbox() {
        assertThat(AgentHarness.valueOf("GEMINI").getResources())
                .contains(new HarnessResource(
                        "/gemini/kratis-gemini-acp-truncation.mjs", "/tmp/kratis-gemini-acp-truncation.mjs"));
        assertThat(AgentHarness.valueOf("CODEX").getResources()).isEmpty();
    }

    @Test
    void gooseAgentCommand_enablesDeveloperBuiltin() {
        // Without --with-builtin developer, goose acp has no shell or file-editor tools.
        assertThat(AgentHarness.valueOf("GOOSE").getAgentCommand()).isEqualTo("goose acp --with-builtin developer");
    }

    @Test
    void mistralSetupCommands_defaultAgentAcceptEdits() {
        // accept-edits auto-approves write/edit; bash still raises HITL.
        assertThat(AgentHarness.valueOf("MISTRAL").getSetupCommands())
                .anySatisfy(command -> assertThat(command).contains("default_agent = \"accept-edits\""));
    }

    @Test
    void openHandsSetupCommands_useProcessRuntimeAndAlwaysAsk() {
        // Docker-in-docker is unavailable in the Kratis sandbox; always-ask routes confirmations to HITL.
        String settings = commandContaining(AgentHarness.valueOf("OPENHANDS"), "agent_settings.json");
        assertThat(settings).contains("\\\"runtime\\\": \\\"process\\\"");
        assertThat(settings).contains("\\\"confirmation_mode\\\": \\\"always-ask\\\"");
    }

    @Test
    void openHandsSetupCommands_pinFixedCliVersionBeforeInstalling() {
        // install.openhands.dev/install.sh hardcodes VERSION="1.14.0" (openhands-tools 1.16.1),
        // whose terminal completion check accepts the pre-command PS1 prompt as command
        // completion and can return an empty observation for the first command. CLI 1.16.0 is
        // the first release built on openhands-tools 1.21.0, which adds the output-changed guard.
        List<String> commands = AgentHarness.valueOf("OPENHANDS").getSetupCommands();

        int download = indexOfContaining(commands, "install.openhands.dev/install.sh");
        int patch = indexOfContaining(commands, "s/^VERSION=\"1.14.0\"/VERSION=\"1.16.0\"/");
        int verify = indexOfContaining(commands, "grep -q");
        int run = indexOfContaining(commands, "sh /tmp/openhands-install.sh");

        assertThat(download).as("must download the upstream install script").isGreaterThanOrEqualTo(0);
        assertThat(patch).as("must repin the stale 1.14.0 version").isGreaterThan(download);
        assertThat(verify).as("must fail if the version pin was not applied").isGreaterThan(patch);
        assertThat(run).as("must run the patched install script").isGreaterThan(verify);
        assertThat(commands)
                .as("must not run the unpatched default installer")
                .noneMatch(command -> command.contains("install.openhands.dev/install.sh | sh"));
    }

    @Test
    void piSetupCommands_declareContextWindowPlaceholder() {
        // Pi rejects models with no contextWindow; ${LLM_CONTEXT_WINDOW} is substituted at provision.
        assertThat(AgentHarness.valueOf("PI").getSetupCommands())
                .anySatisfy(command -> assertThat(command).contains("\"contextWindow\": ${LLM_CONTEXT_WINDOW}"));
    }

    @Test
    void qwenSetupCommands_useDefaultApprovalMode() {
        // yolo would skip HITL; default lets the control plane own permission policy.
        String settings = commandContaining(AgentHarness.valueOf("QWEN"), "settings.json");
        assertThat(settings).contains("\"approvalMode\": \"default\"");
        assertThat(settings).doesNotContain("yolo");
    }

    @Test
    void everyHarness_hasNameSetupAndAgentCommand() {
        // Disabled harnesses are excluded from values(); validate their definitions too.
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(AgentHarness.values()).map(AgentHarness::name).forEach(ids::add);
        ids.addAll(DISABLED_HARNESS_IDS);

        assertThat(ids).hasSizeGreaterThanOrEqualTo(10);
        ids.forEach(id -> {
            AgentHarness harness = AgentHarness.valueOf(id);
            assertThat(harness.getName()).isNotBlank();
            assertThat(harness.getSetupCommands()).isNotEmpty();
            assertThat(harness.getAgentCommand()).isNotBlank();
        });
    }

    @Test
    void disabledHarnesses_areExcludedFromTheCatalogButStillResolvable() {
        // "enabled": false omits a harness from values(); valueOf still resolves so persisted executions load.
        DISABLED_HARNESS_IDS.forEach(id -> assertThat(AgentHarness.valueOf(id)).isNotNull());
        assertThat(Arrays.stream(AgentHarness.values()).map(AgentHarness::name))
                .doesNotContainAnyElementsOf(DISABLED_HARNESS_IDS);
    }

    @Test
    void values_returnsHarnessesAlphabetically() {
        // Classpath discovery has no inherent order; the alphabetical order is the UI dropdown contract.
        List<String> ids =
                Arrays.stream(AgentHarness.values()).map(AgentHarness::name).toList();

        assertThat(ids).isSorted();
    }

    private static int indexOfContaining(List<String> commands, String needle) {
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String commandContaining(AgentHarness harness, String needle) {
        return harness.getSetupCommands().stream()
                .filter(command -> command.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError(harness.name() + " must contain " + needle));
    }

    private static JsonNode geminiSettings() throws Exception {
        String settingsCommand = commandContaining(AgentHarness.valueOf("GEMINI"), "$HOME/.gemini/settings.json");
        String json = settingsCommand
                .substring(settingsCommand.indexOf('\'') + 1, settingsCommand.lastIndexOf('\''))
                .replace("${VIRTUAL_KEY}", "vk-test")
                .replace("${LLM_BASE_URL}", "http://llm.local");
        return MAPPER.readTree(json);
    }

    private static JsonNode openCodeConfig() throws Exception {
        String configCommand = commandContaining(AgentHarness.valueOf("OPENCODE"), "opencode.json");
        String json = configCommand
                .substring(configCommand.indexOf('\'') + 1, configCommand.lastIndexOf('\''))
                .replace("${LLM_MODEL}", "gpt-4o")
                .replace("${LLM_BASE_URL}", "http://llm.local");
        return MAPPER.readTree(json);
    }
}
