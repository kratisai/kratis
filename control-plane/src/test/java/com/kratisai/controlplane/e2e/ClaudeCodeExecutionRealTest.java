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
class ClaudeCodeExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testClaudeCodeInstallationAndExecutionFlow() throws Exception {
        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(),
                "Test Provider",
                ProviderType.OPENAI,
                "sk-mock-key-123",
                dockerAccessibleBaseUrl,
                List.of("claude-sonnet-4-20250514"));
        executeAgentInstallationAndExecutionFlow(modelProvider);
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.CLAUDE_CODE;
    }

    @Override
    protected String getExpectedAgentName() {
        return "@agentclientprotocol/claude-agent-acp";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        // bash per the WireMock scenario
        return List.of(ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // Claude Code uses OpenAI Responses API (SSE streaming via /v1/responses).
        // Claude makes multiple requests: title generation (2x), task execution, tool results.
        // Requests: title → title → Write tool → Write result → Bash tool → Bash result → text done.

        // Title generation: contains specific title generation instruction
        // There are TWO title generation requests (one with stream:true, one without)
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Generate a concise, sentence-case title")
                .sseResponse(LlmResponseBuilders.responsesSseText("{\"title\": \"Write shell script\"}"))
                .build());

        // Task execution: has "tools" array (initial prompt with tool definitions)
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"tools\"")
                .notContains("\"function_call_output\"")
                .sseResponse(
                        LlmResponseBuilders.responsesSseToolCall(
                                "Write",
                                "{\"file_path\":\"/kratis/workspace/kratis_task.sh\",\"content\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\"}"))
                .maxMatches(1)
                .build());

        // Write tool result: request contains tool output → respond with Bash tool call
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"function_call_output\"")
                .contains("Write")
                .sseResponse(
                        LlmResponseBuilders.responsesSseToolCall(
                                "Bash",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Bash tool result: request contains tool output → respond with completion text
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"function_call_output\"")
                .contains("Bash")
                .sseResponse(LlmResponseBuilders.responsesSseText("Task complete."))
                .maxMatches(1)
                .build());

        // Catch-all: any remaining requests get completion text
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.responsesSseText("Task complete."))
                .maxMatches(2)
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // Title gen (2x) + task execution + tool result = at least 3
        return 3;
    }

    @Override
    protected int getExpectedHitlRequestCount() {
        return 2;
    }

    @Override
    protected List<ExpectedFile> getExpectedFiles() {
        return List.of(
                ExpectedFile.withContent("/kratis/workspace/kratis_task.sh", "Hello Kratis - what a lovely day"));
    }
}
