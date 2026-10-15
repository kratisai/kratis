package com.kratisai.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Planning-agent loop configuration: bounds the ReAct iterations the loop runs before giving up.
 */
@Component
@ConfigurationProperties(prefix = "kratis.planning")
public class PlanningProperties {

    private int maxIterations = 25;
    private Duration llmTimeout = Duration.ofSeconds(60);

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public Duration getLlmTimeout() {
        return llmTimeout;
    }

    public void setLlmTimeout(Duration llmTimeout) {
        this.llmTimeout = llmTimeout;
    }
}
