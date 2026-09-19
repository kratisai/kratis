package com.kratisai.controlplane.model;

import java.util.List;

public enum AgentHarness {
    AIDER(
            "Aider",
            List.of(
                    "export PATH=$HOME/.local/bin:$HOME/.cargo/bin:$PATH",
                    "export OPENAI_API_KEY=${VIRTUAL_KEY}",
                    // Aider uses OPENAI_API_BASE (not OPENAI_BASE_URL) for custom OpenAI-compatible endpoints
                    "export OPENAI_API_BASE=${LLM_BASE_URL}/v1",
                    "export OPENAI_BASE_URL=${LLM_BASE_URL}/v1",
                    "export AIDER_MODEL=openai/${LLM_MODEL}",
                    // Prevent litellm from routing to Vertex AI by using local model cost map
                    "export LITELLM_LOCAL_MODEL_COST_MAP=True",
                    // Create aider config to suppress non-essential prompts.
                    "mkdir -p $HOME && echo 'show-release-notes: false' > $HOME/.aider.conf.yml && echo 'check-update: false' >> $HOME/.aider.conf.yml",
                    "git clone --depth 1 https://github.com/jorgejhms/aider-acp.git $HOME/aider-acp",
                    "npm install --prefix $HOME/aider-acp",
                    "npm run build --prefix $HOME/aider-acp",
                    "curl -LsSf https://astral.sh/uv/install.sh | sh",
                    "uv tool install --python 3.12 aider-chat",
                    // TODO - investigate / validate this very suspicious workaround
                    // aider-acp hardcodes the model as "gemini/gemini-2.5-flash" and passes it to aider
                    // via --model flag. We cannot patch aider-acp, so we create a wrapper script for
                    // aider that intercepts the --model argument and replaces it with the correct model.
                    "mkdir -p $HOME/aider-wrapper",
                    "echo '#!/bin/bash' > $HOME/aider-wrapper/aider && echo 'args=(\"$@\")' >> $HOME/aider-wrapper/aider && echo 'for i in \"${!args[@]}\"; do' >> $HOME/aider-wrapper/aider && echo '  if [[ \"${args[$i]}\" == \"--model\" ]]; then' >> $HOME/aider-wrapper/aider && echo '    args[$((i+1))]=\"__MODEL__\"' >> $HOME/aider-wrapper/aider && echo '  fi' >> $HOME/aider-wrapper/aider && echo 'done' >> $HOME/aider-wrapper/aider && echo 'exec \"__AIDER__\" --yes \"${args[@]}\"' >> $HOME/aider-wrapper/aider",
                    "sed -i \"s|__MODEL__|${AIDER_MODEL}|\" $HOME/aider-wrapper/aider && sed -i \"s|__AIDER__|$(which aider)|\" $HOME/aider-wrapper/aider",
                    "chmod +x $HOME/aider-wrapper/aider",
                    "echo '[Aider] Wrapper script:' && cat $HOME/aider-wrapper/aider"),
            "PATH=$HOME/aider-wrapper:$PATH node $HOME/aider-acp/dist/index.js"),
    CLAUDE_CODE(
            "Claude Code",
            List.of(
                    "export PATH=$HOME/claude/node_modules/.bin:$PATH",
                    "export ANTHROPIC_API_KEY=${VIRTUAL_KEY}",
                    "export ANTHROPIC_BASE_URL=${LLM_BASE_URL}",
                    "export DISABLE_AUTOUPDATER=1",
                    "mkdir -p ~/.claude",
                    "echo '{\"model\": \"${LLM_MODEL}\"}' > ~/.claude/settings.json",
                    "npm install --prefix $HOME/claude @agentclientprotocol/claude-agent-acp@v0.54.1"),
            "claude-agent-acp"),
    CODEX(
            "Codex",
            List.of(
                    "export NO_BROWSER=1",
                    "export PATH=$HOME/codex/node_modules/.bin:$PATH",
                    "export OPENAI_API_KEY=${VIRTUAL_KEY}",
                    "export CODEX_API_KEY=${VIRTUAL_KEY}",
                    "export OPENAI_BASE_URL=${LLM_BASE_URL}/v1",
                    // Use CODEX_CONFIG to fully control sandbox mode (no bwrap) and approval policy (HITL)
                    // "export CODEX_CONFIG='{\"sandbox\":\"danger-full-access\",\"approval\":\"always\"}'",
                    // Above config is incorrect - resorting to agent-full-access until we can fix it.
                    "export INITIAL_AGENT_MODE=agent-full-access",
                    "mkdir -p $HOME/.codex",
                    "echo 'preferred_auth_method = \"apikey\"' > $HOME/.codex/config.toml",
                    "echo 'model = \"${LLM_MODEL}\"' >> $HOME/.codex/config.toml",
                    "echo 'model_provider = \"proxy\"' >> $HOME/.codex/config.toml",
                    "echo '[model_providers.proxy]' >> $HOME/.codex/config.toml",
                    "echo 'name = \"OpenAI using LLM proxy\"' >> $HOME/.codex/config.toml",
                    "echo 'base_url = \"${LLM_BASE_URL}/v1\"' >> $HOME/.codex/config.toml",
                    "echo 'wire_api = \"responses\"' >> $HOME/.codex/config.toml",
                    "echo 'env_key = \"CODEX_API_KEY\"' >> $HOME/.codex/config.toml",
                    "npm install --prefix $HOME/codex @agentclientprotocol/codex-acp"),
            "codex-acp"),
    GEMINI(
            "Gemini",
            List.of(
                    "export PATH=$HOME/gemini/node_modules/.bin:$PATH",
                    "export GEMINI_API_KEY=${VIRTUAL_KEY}",
                    "export GOOGLE_API_KEY=${VIRTUAL_KEY}",
                    // Route Gemini through LiteLLM like all other agents.
                    "export GOOGLE_GEMINI_BASE_URL=${LLM_BASE_URL}",
                    // Force REST mode — Gemini CLI defaults to gRPC which is incompatible with WireMock (HTTP/1.1)
                    "export GEMINI_REST=true",
                    "export GOOGLE_REST=true",
                    // Explicitly set the model name so Gemini CLI doesn't try to auto-detect or use a default
                    "export GEMINI_MODEL=${LLM_MODEL}",
                    // Provide a dummy project to bypass GCP project validation
                    "export GOOGLE_CLOUD_PROJECT=kratis-test",
                    "mkdir -p $HOME/.gemini",
                    "echo '{\"security\":{\"auth\":{\"selectedType\":\"gemini-api-key\"}}, \"apiKey\":\"${VIRTUAL_KEY}\", \"baseUrl\":\"${LLM_BASE_URL}\"}' > $HOME/.gemini/settings.json",
                    "npm install --prefix $HOME/gemini @google/gemini-cli"),
            "gemini --acp"),
    GOOSE(
            "Goose",
            List.of(
                    "export PATH=$HOME/.local/bin:$PATH",
                    "mkdir -p ~/.local/bin /tmp/goose-extract",
                    "curl -fsSL -o /tmp/goose.tar.bz2 \"https://github.com/aaif-goose/goose/releases/download/stable/goose-$(uname -m)-unknown-linux-gnu.tar.bz2\"",
                    "tar -xjf /tmp/goose.tar.bz2 -C /tmp/goose-extract",
                    "mv /tmp/goose-extract/goose ~/.local/bin/goose",
                    "chmod +x ~/.local/bin/goose",
                    // Configure Goose with full provider config
                    "mkdir -p ~/.config/goose",
                    "printf 'provider: openai\\nmodel: %s\\nproviders:\\n  openai:\\n    type: openai\\n    api_key: %s\\n    base_url: %s/v1\\n' \"${LLM_MODEL}\" \"${VIRTUAL_KEY}\" \"${LLM_BASE_URL}\" > ~/.config/goose/config.yaml",
                    // Set environment variables for Goose's OpenAI provider
                    "export GOOSE_PROVIDER=openai",
                    "export GOOSE_MODEL=${LLM_MODEL}",
                    "export GOOSE_MODE=auto",
                    "export OPENAI_API_KEY=${VIRTUAL_KEY}",
                    "export OPENAI_BASE_URL=${LLM_BASE_URL}/v1"),
            // The --with-builtin flag enables developer extensions (shell, file editor).
            "goose acp --with-builtin developer"),
    MISTRAL(
            "Mistral",
            List.of(
                    "export PATH=$HOME/.local/bin:$PATH",
                    // MISTRAL_API_KEY is read by Vibe via api_key_env_var in the provider config.
                    "export MISTRAL_API_KEY=${VIRTUAL_KEY}",
                    "curl -LsSf https://mistral.ai/vibe/install.sh | bash",
                    // Configure Mistral Vibe via config.toml to use LiteLLM
                    "mkdir -p $HOME/.vibe",
                    "printf 'default_agent = \"accept-edits\"\\nactive_model = \"%s\"\\n\\n[[providers]]\\nname = \"mistral\"\\napi_base = \"%s/v1\"\\napi_key_env_var = \"MISTRAL_API_KEY\"\\napi_style = \"openai\"\\n\\n[[models]]\\nname = \"%s\"\\nprovider = \"mistral\"\\nalias = \"%s\"\\n' \"${LLM_MODEL}\" \"${LLM_BASE_URL}\" \"${LLM_MODEL}\" \"${LLM_MODEL}\" > $HOME/.vibe/config.toml",
                    "echo '[Mistral Config] config.toml:' && cat $HOME/.vibe/config.toml"),
            "vibe-acp"),
    OPENCODE(
            "OpenCode",
            List.of(
                    "export PATH=$HOME/.opencode/bin:$PATH",
                    // OpenCode's shell tool defaults to a short timeout and clamps agent-chosen
                    // values, which kills long build and test commands in the sandbox.
                    "export OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS=3600000",
                    // Install OpenCode first
                    "curl --retry 5 --retry-delay 2 -fsSL -o /tmp/install.sh https://opencode.ai/install && bash /tmp/install.sh",
                    // Create OpenCode config
                    "mkdir -p $HOME/.config/opencode",
                    "echo '{\"$schema\":\"https://opencode.ai/config.json\",\"model\":\"litellm/${LLM_MODEL}\",\"permission\":{\"edit\":\"ask\",\"bash\":\"ask\"},\"provider\":{\"litellm\":{\"npm\":\"@ai-sdk/openai-compatible\",\"name\":\"LiteLLM\",\"options\":{\"baseURL\":\"${LLM_BASE_URL}/v1\", \"apiKey\": \"{env:LLM_API_KEY}\"},\"models\":{\"${LLM_MODEL}\":{\"name\":\"${LLM_MODEL}\"}}}}}' > $HOME/.config/opencode/opencode.json",
                    "export LLM_API_KEY=${VIRTUAL_KEY}",
                    "echo '[OpenCode Config] opencode.json:' && cat $HOME/.config/opencode/opencode.json"),
            "opencode acp"),
    OPENHANDS(
            "OpenHands",
            List.of(
                    "export PATH=$HOME/.local/bin:$PATH",
                    "export OPENHANDS_SUPPRESS_BANNER=1",
                    "mkdir -p $HOME/.openhands",
                    "echo \"{\\\"llm\\\": {\\\"api_key\\\": \\\"${VIRTUAL_KEY}\\\", \\\"base_url\\\": \\\"${LLM_BASE_URL}/v1\\\", \\\"model\\\": \\\"openai/${LLM_MODEL}\\\"}, \\\"sandbox\\\": {\\\"runtime\\\": \\\"process\\\"}, \\\"runtime\\\": \\\"process\\\", \\\"conversation_settings\\\": {\\\"confirmation_mode\\\": \\\"always-ask\\\"}}\" > $HOME/.openhands/agent_settings.json",
                    "curl -fsSL https://install.openhands.dev/install.sh | sh"),
            "openhands acp"),
    PI(
            "PI",
            List.of(
                    "export PATH=$HOME/pi/bin:$HOME/pi/node_modules/.bin:$HOME/pi:$PATH",
                    // Set OPENAI_API_KEY and OPENAI_BASE_URL for Pi's built-in openai provider
                    "export OPENAI_API_KEY=${VIRTUAL_KEY}",
                    "export OPENAI_BASE_URL=${LLM_BASE_URL}/v1",
                    "npm install --prefix $HOME/pi @earendil-works/pi-coding-agent",
                    "npm install --prefix $HOME/pi pi-acp",
                    // Pi config files - use "openai" provider name so pi-coding-agent recognizes it
                    "mkdir -p $HOME/.pi $HOME/.pi/agent",
                    // 1. auth.json - API key for openai provider
                    "echo '{\"openai\": {\"type\": \"api_key\", \"key\": \"${VIRTUAL_KEY}\"}}' > $HOME/.pi/auth.json",
                    "cp $HOME/.pi/auth.json $HOME/.pi/agent/auth.json",
                    // 2. models.json - openai provider with LLM_BASE_URL and LLM_MODEL
                    "echo '{\"providers\": {\"openai\": {\"baseUrl\": \"${LLM_BASE_URL}/v1\", \"api\": \"openai-completions\", \"models\": [{\"id\": \"${LLM_MODEL}\", \"name\": \"${LLM_MODEL}\", \"provider\": \"openai\", \"api\": \"openai-completions\", \"baseUrl\": \"${LLM_BASE_URL}/v1\", \"contextWindow\": 128000, \"maxTokens\": 4096}]}}}' > $HOME/.pi/models.json",
                    "cp $HOME/.pi/models.json $HOME/.pi/agent/models.json",
                    // 3. settings.json - default model and provider
                    "echo '{\"defaultModel\": \"${LLM_MODEL}\", \"defaultProvider\": \"openai\"}' > $HOME/.pi/settings.json",
                    "cp $HOME/.pi/settings.json $HOME/.pi/agent/settings.json",
                    // Create a wrapper script for the pi command that pi-acp can spawn.
                    "mkdir -p $HOME/pi/bin",
                    "printf '#!/bin/bash\\nexec %s/pi/node_modules/.bin/pi \"$@\"\\n' \"$HOME\" > $HOME/pi/bin/pi",
                    "chmod +x $HOME/pi/bin/pi",
                    "echo '[Pi Config] pi wrapper:' && cat $HOME/pi/bin/pi && which pi"),
            "node $HOME/pi/node_modules/pi-acp/dist/index.js \"$@\""),
    QWEN(
            "Qwen",
            List.of(
                    "export PATH=$HOME/.local/bin:$PATH",
                    "export OPENAI_API_KEY=${VIRTUAL_KEY}",
                    "mkdir -p $HOME/.qwen",
                    "echo '{\"security\": {\"auth\": {\"selectedType\": \"openai\"}}, \"model\": {\"name\": \"${LLM_MODEL}\"}, \"modelProviders\": {\"openai\": [{\"id\": \"${LLM_MODEL}\", \"name\": \"${LLM_MODEL}\", \"baseUrl\": \"${LLM_BASE_URL}/v1\", \"envKey\": \"OPENAI_API_KEY\"}]}, \"tools\": {\"approvalMode\": \"default\"}, \"$version\": 3}' > $HOME/.qwen/settings.json",
                    "echo '[Qwen Config] settings.json:' && cat $HOME/.qwen/settings.json",
                    "curl -fsSL https://qwen-code-assets.oss-cn-hangzhou.aliyuncs.com/installation/install-qwen-standalone.sh | bash"),
            "qwen --acp"),
    ;

    private final String name;
    private final List<String> setupCommands;
    private final String agentCommand;

    AgentHarness(String name, List<String> setupCommands, String agentCommand) {
        this.name = name;
        this.setupCommands = setupCommands;
        this.agentCommand = agentCommand;
    }

    public String getName() {
        return name;
    }

    public List<String> getSetupCommands() {
        return setupCommands;
    }

    public String getAgentCommand() {
        return agentCommand;
    }
}
