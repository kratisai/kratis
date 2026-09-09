package com.kratisai.controlplane;

/**
 * Shared per-upstream pre-flight stub presets for {@link WireMockLlmServer}. Each method registers
 * the metadata GET endpoints an upstream solution's SDK may call before sending prompts (e.g.
 * model-list or user-info checks). Add presets here where they are shared by at least 2 tests.
 */
public final class LlmMockScenarios {

    private LlmMockScenarios() {
        // Utility class
    }

    /**
     * Registers the OpenAI-compatible {@code GET /v1/models} pre-flight stub.
     *
     * @param server the WireMock LLM server to configure
     */
    public static void openAiCompat(WireMockLlmServer server) {
        server.stubGet("/v1/models", LlmResponseBuilders.openAiModelsList());
    }

    /**
     * Registers the Mistral Vibe pre-flight stubs: the OpenAI-compatible model list plus
     * {@code GET /v1/users/me}.
     *
     * @param server the WireMock LLM server to configure
     */
    public static void mistral(WireMockLlmServer server) {
        openAiCompat(server);
        server.stubGet("/v1/users/me", LlmResponseBuilders.openAiUserInfo());
    }

    /**
     * Registers the Gemini CLI {@code GET /gemini/v1beta/models} pre-flight stub.
     *
     * @param server the WireMock LLM server to configure
     */
    public static void gemini(WireMockLlmServer server) {
        server.stubGet("/gemini/v1beta/models", LlmResponseBuilders.geminiModelsList());
    }
}
