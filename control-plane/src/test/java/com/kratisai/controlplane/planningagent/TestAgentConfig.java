package com.kratisai.controlplane.planningagent;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test configuration that overrides the production AgentConfig beans with test doubles. Only
 * overrides the EmbeddingModel to return our mock embedding model.
 */
@TestConfiguration
public class TestAgentConfig {

    @Bean
    public MockEmbeddingModel mockEmbeddingModel() {
        return new MockEmbeddingModel();
    }
}
