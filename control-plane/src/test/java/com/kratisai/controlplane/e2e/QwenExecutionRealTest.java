package com.kratisai.controlplane.e2e;

import com.kratisai.controlplane.HttpRequestMatcher;
import com.kratisai.controlplane.LlmMockScenarios;
import com.kratisai.controlplane.LlmResponseBuilders;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import java.util.List;
import org.junit.jupiter.api.Test;

@SpringIntegrationTest
class QwenExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testQwenInstallationAndExecutionFlow() throws Exception {
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
        return AgentHarness.QWEN;
    }

    @Override
    protected String getExpectedAgentName() {
        // Qwen's ACP adapter does not advertise agentInfo.name.
        return null;
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                .sseResponse(
                        LlmResponseBuilders.openAiSseToolCall(
                                "run_shell_command",
                                "{\"command\":\"echo '#!/bin/bash\\necho \\\"Hello Kratis - what a lovely day\\\"' > /kratis/workspace/kratis_task.sh && chmod +x /kratis/workspace/kratis_task.sh && bash /kratis/workspace/kratis_task.sh\"}"))
                .maxMatches(1)
                .build());

        // Catch-all for any additional requests
        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(LlmResponseBuilders.openAiSseText(
                        "Task complete. The Hello Kratis script was created and executed successfully."))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
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
