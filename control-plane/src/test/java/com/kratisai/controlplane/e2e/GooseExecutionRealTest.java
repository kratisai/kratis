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
class GooseExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testGooseInstallationAndExecutionFlow() throws Exception {
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
        return AgentHarness.GOOSE;
    }

    @Override
    protected String getExpectedAgentName() {
        return "goose";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        // edit + shell per the WireMock scenario
        return List.of(ActivityType.EDITED, ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // Goose uses OpenAI Chat Completions API with streaming (stream:true)
        // Goose makes a title generation request first, then the task execution request.
        //
        // Goose's built-in tool names (exposed to the LLM via its tool definitions):
        //   - "write"  → create/overwrite a file (params: {path, content})
        //   - "shell"  → execute a shell command  (params: {command})
        //   - "read", "edit", "tree", "analyze", etc.
        // The LLM MUST call these exact names — Goose rejects unknown tool names with
        // "Tool 'X' not found. Available tools: [...]".

        // Title generation: system prompt contains "Generate a short title"
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Generate a short title")
                .sseResponse(LlmResponseBuilders.openAiSseText("Shell script task"))
                .maxMatches(1)
                .build());

        // Task execution: contains the task prompt but NOT the title generation prompt.
        // Use Goose's actual tool name "write" (not "write_file").
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .notContains("Generate a short title")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "write",
                                "{\"path\":\"/kratis/workspace/kratis_task.sh\",\"content\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\"}"))
                .maxMatches(1)
                .build());

        // After write tool result: request contains tool role message.
        // Use Goose's actual tool name "shell" (not "execute_command").
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"role\":\"tool\"")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "shell",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Catch-all: completion text (follow-up requests after tool execution)
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .notContains("Generate a short title")
                .notContains("Write a shell script")
                .notContains("\"role\":\"tool\"")
                .sseResponse(LlmResponseBuilders.openAiSseText("Task complete."))
                .maxMatches(2)
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // Title gen + task execution + tool result = at least 3
        return 3;
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
