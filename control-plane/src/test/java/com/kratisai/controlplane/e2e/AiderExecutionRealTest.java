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
class AiderExecutionRealTest extends AbstractAgentExecutionRealTest {

    @Test
    void testAiderInstallationAndExecutionFlow() throws Exception {
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
        return AgentHarness.AIDER;
    }

    @Override
    protected String getExpectedAgentName() {
        // aider-acp does not advertise agentInfo.name, and applies edits via SEARCH/REPLACE
        // text rather than tool calls.
        return null;
    }

    @Override
    protected void configureWireMockScenario() {
        LlmMockScenarios.openAiCompat(wireMockLlmServer);

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .contains("Write a shell script")
                // Aider applies changes via SEARCH/REPLACE blocks in the text response.
                .sseResponse(LlmResponseBuilders.openAiSseText("""
                        I'll create the shell script.

                        kratis_task.sh
                        ```bash
                        <<<<<<< SEARCH
                        =======
                        #!/bin/bash
                        echo "Hello Kratis - what a lovely day"
                        >>>>>>> REPLACE
                        ```"""))
                .maxMatches(1)
                .build());

        wireMockLlmServer.addMatcher(HttpRequestMatcher.builder()
                .sseResponse(
                        LlmResponseBuilders.openAiSseText("Task complete. Output: Hello Kratis - what a lovely day"))
                .build());
    }

    @Override
    protected int getExpectedMinimumLlmRequests() {
        // SEARCH/REPLACE request + catch-all completion = at least 2
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
