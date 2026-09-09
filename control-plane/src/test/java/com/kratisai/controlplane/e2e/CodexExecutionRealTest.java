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
class CodexExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testCodexInstallationAndExecutionFlow() throws Exception {
        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(),
                "Test Provider",
                ProviderType.OPENAI,
                "sk-mock-key-123",
                dockerAccessibleBaseUrl,
                List.of("gpt-4o"));
        executeAgentInstallationAndExecutionFlow(modelProvider);
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.CODEX;
    }

    @Override
    protected String getExpectedAgentName() {
        return "@agentclientprotocol/codex-acp";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // Codex uses OpenAI Responses API (SSE streaming) with write_file/execute_command
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"tools\"")
                .sseResponse(
                        LlmResponseBuilders.responsesSseToolCall(
                                "exec_command",
                                "{\"cmd\":\"echo '#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"' > /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"function_call_output\"")
                .sseResponse(
                        LlmResponseBuilders.responsesSseToolCall(
                                "exec_command",
                                "{\"cmd\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Catch-all: completion text (follow-up requests after tool execution)
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .notContains("\"tools\"")
                .notContains("\"function_call_output\"")
                .sseResponse(LlmResponseBuilders.responsesSseText("Task complete."))
                .maxMatches(2)
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // Initial tool call + tool result = at least 2
        return 2;
    }

    @Override
    protected int getExpectedHitlRequestCount() {
        return 0;
    }

    @Override
    protected List<ExpectedFile> getExpectedFiles() {
        return List.of(
                ExpectedFile.withContent("/kratis/workspace/kratis_task.sh", "Hello Kratis - what a lovely day"));
    }
}
