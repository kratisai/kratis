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
class PiExecutionRealTest extends AbstractAgentExecutionRealTest {

    private static final Logger logger = LoggerFactory.getLogger(PiExecutionRealTest.class);

    @Test
    void testPiInstallationAndExecutionFlow() throws Exception {
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
            capturePiLogs();
        }
    }

    /**
     * Captures Pi log files from the container for debugging.
     * Pi writes logs to ~/.local/share/pi/log/
     */
    private void capturePiLogs() {
        try {
            DockerExecVerifier verifier = createDockerVerifier();
            logger.info("[Pi Debug] === Capturing Pi log files ===");
            // List log files
            var listResult = verifier.executeInContainer(
                    "sh", "-c", "ls -la $HOME/.local/share/pi/log/ 2>/dev/null || echo 'No log directory'");
            logger.info("[Pi Debug] Log files: {}", new String(listResult.output()));
            // Read the most recent log file
            var latestLog = verifier.executeInContainer(
                    "sh", "-c", "tail -200 $HOME/.local/share/pi/log/*.log 2>/dev/null || echo 'No log files found'");
            logger.info("[Pi Debug] Latest log content:\n{}", new String(latestLog.output()));
            // Check Pi configuration files
            logger.info("[Pi Debug] === Pi Configuration ===");
            var authConfig = verifier.executeInContainer("sh", "-c", "cat $HOME/.pi/agent/auth.json");
            logger.info("[Pi Debug] auth.json: {}", new String(authConfig.output()));
            var modelsConfig = verifier.executeInContainer("sh", "-c", "cat $HOME/.pi/agent/models.json");
            logger.info("[Pi Debug] models.json: {}", new String(modelsConfig.output()));
            var settingsConfig = verifier.executeInContainer("sh", "-c", "cat $HOME/.pi/agent/settings.json");
            logger.info("[Pi Debug] settings.json: {}", new String(settingsConfig.output()));
        } catch (Exception e) {
            logger.warn("[Pi Debug] Failed to capture Pi logs: {}", e.getMessage());
        }
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.PI;
    }

    @Override
    protected String getExpectedAgentName() {
        return "pi-acp";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // Pi uses OpenAI Chat Completions API with streaming (SSE) and tools: read, bash, edit, write
        // First request: contains the task prompt → respond with write tool to create the file
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "write",
                                "{\"path\":\"/kratis/workspace/kratis_task.sh\",\"content\":\"#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"\\n\"}"))
                .maxMatches(1)
                .build());

        // Second request: after write tool result → respond with bash tool to make executable and run
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("\"role\":\"tool\"")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "bash",
                                "{\"command\":\"chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Catch-all for any additional requests
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.openAiSseText("Task complete."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        return 3;
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
