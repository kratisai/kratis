package com.kratisai.controlplane.e2e;

import com.kratisai.controlplane.HttpRequestMatcher;
import com.kratisai.controlplane.LlmMockScenarios;
import com.kratisai.controlplane.LlmResponseBuilders;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import java.util.List;
import org.junit.jupiter.api.Test;

@SpringIntegrationTest
class GeminiExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testGeminiInstallationAndExecutionFlow() throws Exception {
        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        // Gemini CLI uses the Gemini API format, so we need ProviderType.GOOGLE
        // and a valid Gemini model name that Gemini CLI recognizes
        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(),
                "Test Provider",
                ProviderType.GOOGLE,
                "sk-mock-key-123",
                dockerAccessibleBaseUrl,
                List.of("gemini-flash-latest"));
        executeAgentInstallationAndExecutionFlow(modelProvider);
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.GEMINI;
    }

    @Override
    protected String getExpectedAgentName() {
        return "gemini-cli";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        // command execution (COMMAND) per the WireMock scenario
        return List.of(ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.gemini(wireMockLlmServer);

        // Gemini CLI uses SSE streaming (streamGenerateContent?alt=sse) so responses must be
        // in SSE format with "data: " prefixed JSON chunks.
        // The Gemini CLI request body uses the Gemini API format with "contents" and "systemInstruction"
        // fields, which differ from OpenAI format. We use sequential matchers (order-based with maxMatches)
        // rather than body content matching, since the exact request body content varies.
        // IMPORTANT: Gemini CLI's write_file tool doesn't create actual files in the container —
        // it uses an internal virtual filesystem. The run_shell_command tool DOES execute via ACP
        // in the container, so we skip write_file entirely and use run_shell_command as the first
        // tool call to create and run the script in one command.
        // First request: initial prompt → respond with run_shell_command to create and run the script
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(
                        LlmResponseBuilders.geminiSseToolCall(
                                "run_shell_command",
                                "{\"command\":\"echo '#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"' > /kratis/workspace/kratis_task.sh && chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Second request (catch-all): final text response
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.geminiSseText("Task complete."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // Initial tool call + completion = at least 2
        return 2;
    }

    @Override
    protected int getExpectedHitlRequestCount() {
        return 1;
    }

    @Override
    protected List<ExpectedFile> getExpectedFiles() {
        return List.of(
                ExpectedFile.withContent("/kratis/workspace/kratis_task.sh", "Hello Kratis - what a lovely day"));
    }
}
