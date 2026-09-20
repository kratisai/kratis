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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringIntegrationTest
class OpenCodeExecutionRealTest extends AbstractAgentExecutionRealTest {

    private static final Logger logger = LoggerFactory.getLogger(OpenCodeExecutionRealTest.class);

    @Test
    void testOpenCodeInstallationAndExecutionFlow() throws Exception {
        String dockerAccessibleBaseUrl = wireMockLlmServer.getBaseUrl(liteLLMProperties);
        ModelProvider modelProvider = testDataFactory.createModelProviderWithLiteLLM(
                testContext.team(),
                "Test Provider",
                ProviderType.OPENAI,
                "sk-mock-key-123",
                dockerAccessibleBaseUrl,
                List.of("gpt-4o"));
        try {
            executeAgentInstallationAndExecutionFlow(modelProvider);
        } finally {
            // Always capture logs, even if the test fails
            captureOpenCodeLogs();
        }
    }

    /**
     * Captures OpenCode log files from the container for debugging.
     * OpenCode writes logs to ~/.local/share/opencode/log/
     */
    private void captureOpenCodeLogs() {
        try {
            DockerExecVerifier verifier = createDockerVerifier();
            logger.info("[OpenCode Debug] === Capturing OpenCode log files ===");
            // List log files
            var listResult = verifier.executeInContainer(
                    "sh", "-c", "ls -la $HOME/.local/share/opencode/log/ 2>/dev/null || echo 'No log directory'");
            logger.info("[OpenCode Debug] Log files: {}", new String(listResult.output()));
            // Read the most recent log file
            var latestLog = verifier.executeInContainer(
                    "sh",
                    "-c",
                    "tail -200 $HOME/.local/share/opencode/log/*.log 2>/dev/null || echo 'No log files found'");
            logger.info("[OpenCode Debug] Latest log content:\n{}", new String(latestLog.output()));
        } catch (Exception e) {
            logger.warn("[OpenCode Debug] Failed to capture OpenCode logs: {}", e.getMessage());
        }
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.OPENCODE;
    }

    @Override
    protected String getExpectedAgentName() {
        return "OpenCode";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.EDITED, ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // Title generation: contains "Generate a brief title" instruction
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Generate a brief title")
                .sseResponse(LlmResponseBuilders.openAiSseText("Write shell script"))
                .build());

        // Task execution: contains the task prompt with write tool call
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "write",
                                "{\"filePath\":\"/kratis/workspace/kratis_task.sh\",\"content\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\"}"))
                .maxMatches(1)
                .build());

        // After write: bash command
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"tool\"")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "bash",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Catch-all: completion text
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.openAiSseText("Task complete."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // Title gen + write_file + execute_command = at least 3
        return 3;
    }

    @Override
    protected int getExpectedHitlRequestCount() {
        // Gated fs/write_text_file for the task script (tool kind "write") plus
        // the agent's own permission requests.
        return 3;
    }

    @Override
    protected List<ExpectedFile> getExpectedFiles() {
        return List.of(
                ExpectedFile.withContent("/kratis/workspace/kratis_task.sh", "Hello Kratis - what a lovely day"));
    }
}
