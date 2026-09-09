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
class OpenhandsExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testOpenhandsInstallationAndExecutionFlow() throws Exception {
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
        return AgentHarness.OPENHANDS;
    }

    @Override
    protected String getExpectedAgentName() {
        return "OpenHands CLI ACP Agent";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // OpenHands uses OpenAI Chat Completions API (non-streaming JSON) with tools:
        //   - "terminal"     → execute a bash command (required params: command, security_risk)
        //   - "file_editor"  → create/edit files       (required params: command, path, security_risk)
        // The "security_risk" field is REQUIRED by OpenHands tool validation — omitting it causes
        // "Failed to provide security_risk field in tool 'terminal'" error.

        // Step 1: Create the shell script file using file_editor
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .notContains("\"role\":\"tool\"")
                .jsonResponse(
                        LlmResponseBuilders.openAiToolCall(
                                "file_editor",
                                "{\"command\":\"create\",\"path\":\"/kratis/workspace/kratis_task.sh\",\"file_text\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\",\"security_risk\":\"LOW\"}"))
                .maxMatches(1)
                .build());

        // Step 2: Make it executable and run it using terminal
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"role\":\"tool\"")
                .contains("file_editor")
                .jsonResponse(
                        LlmResponseBuilders.openAiToolCall(
                                "terminal",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\",\"security_risk\":\"LOW\"}"))
                .maxMatches(1)
                .build());

        // Catch-all: completion text
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .jsonResponse(LlmResponseBuilders.openAiText("Task complete."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // file_editor + terminal = at least 2
        return 2;
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
