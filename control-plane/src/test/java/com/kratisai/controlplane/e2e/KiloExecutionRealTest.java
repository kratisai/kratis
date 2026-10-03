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
class KiloExecutionRealTest extends AbstractAgentExecutionRealTest {

    private static final Logger logger = LoggerFactory.getLogger(KiloExecutionRealTest.class);

    @Test
    void testKiloInstallationAndExecutionFlow() throws Exception {
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
            captureKiloLogs();
        }
    }

    /**
     * Captures Kilo log files from the container for debugging.
     * Kilo (OpenCode fork) writes logs to ~/.local/share/kilo/log/
     */
    private void captureKiloLogs() {
        try {
            DockerExecVerifier verifier = createDockerVerifier();
            logger.info("[Kilo Debug] === Capturing Kilo log files ===");
            var listResult = verifier.executeInContainer(
                    "sh", "-c", "ls -la $HOME/.local/share/kilo/log/ 2>/dev/null || echo 'No log directory'");
            logger.info("[Kilo Debug] Log files: {}", new String(listResult.output()));
            var latestLog = verifier.executeInContainer(
                    "sh", "-c", "tail -200 $HOME/.local/share/kilo/log/*.log 2>/dev/null || echo 'No log files found'");
            logger.info("[Kilo Debug] Latest log content:\n{}", new String(latestLog.output()));
        } catch (Exception e) {
            logger.warn("[Kilo Debug] Failed to capture Kilo logs: {}", e.getMessage());
        }
    }

    @Override
    protected AgentHarness getHarness() {
        return AgentHarness.valueOf("KILO");
    }

    @Override
    protected String getExpectedAgentName() {
        // Verified live: kilo acp initialize advertises agentInfo.name "Kilo".
        return "Kilo";
    }

    @Override
    protected List<ActivityType> getExpectedActivityTypes() {
        return List.of(ActivityType.EDITED, ActivityType.COMMAND);
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        // Title generation and task turns both quote task text; the task matcher runs first.
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
        // Task write turn + bash turn = at least 2
        return 2;
    }

    @Override
    protected int getExpectedHitlRequestCount() {
        // Kilo requests its own permission for the edit (verified in a live kilo acp session)
        // plus the gated fs/write_text_file client-capability write (kind "write") and
        // the agent permission for the bash command = 3.
        return 3;
    }

    @Override
    protected List<ExpectedFile> getExpectedFiles() {
        return List.of(
                ExpectedFile.withContent("/kratis/workspace/kratis_task.sh", "Hello Kratis - what a lovely day"));
    }

    @Override
    protected String getExpectedToolOutputSubstring() {
        return "Hello Kratis - what a lovely day";
    }
}
