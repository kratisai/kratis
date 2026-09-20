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
class MistralExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testMistralInstallationAndExecutionFlow() throws Exception {
        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        // Mistral Vibe uses "mistral-vibe-cli-latest" as its default model name.
        // We must register this model with LiteLLM so it routes to WireMock instead of the real Mistral API.
        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(),
                "Test Provider",
                ProviderType.OPENAI,
                "sk-mock-key-123",
                dockerAccessibleBaseUrl,
                List.of("mistral-vibe-cli-latest"));
        executeAgentInstallationAndExecutionFlow(modelProvider);
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.MISTRAL;
    }

    @Override
    protected String getExpectedAgentName() {
        return "@mistralai/mistral-vibe";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.mistral(wireMockLlmServer);

        // Mistral Vibe uses SSE streaming with write_file/bash tool names
        // Note: write_file uses "file_path" parameter (not "path")
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .sseResponse(
                        LlmResponseBuilders.mistralSseToolCall(
                                "write_file",
                                "{\"file_path\":\"/kratis/workspace/kratis_task.sh\",\"content\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\"}"))
                .maxMatches(1)
                .build());

        // Mistral Vibe uses "bash" tool (not "execute_command") for shell commands
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"tool\"")
                .sseResponse(
                        LlmResponseBuilders.mistralSseToolCall(
                                "bash",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.mistralSseText("Task complete."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // write_file + bash tool = at least 2
        return 2;
    }

    @Override
    protected int getExpectedHitlRequestCount() {
        // accept-edits auto-approves write_file/edit; bash still requires HITL.
        // write_file (auto) + bash tool (HITL) + any secondary bash/HITL from vibe = 2,
        // plus the gated fs/write_text_file client-capability write (kind "write") = 3.
        return 3;
    }

    @Override
    protected List<ExpectedFile> getExpectedFiles() {
        return List.of(
                ExpectedFile.withContent("/kratis/workspace/kratis_task.sh", "Hello Kratis - what a lovely day"));
    }
}
