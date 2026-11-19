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
    private Duration llmTimeout = Duration.ofMinutes(2);
    private Titles titles = new Titles();

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

    public Titles getTitles() {
        return titles;
    }

    public void setTitles(Titles titles) {
        this.titles = titles;
    }

    public static class Titles {

        private int minWords = 8;
        private int maxWords = 30;
        private int maxMessages = 10;

        public int getMinWords() {
            return minWords;
        }

        public void setMinWords(int minWords) {
            this.minWords = minWords;
        }

        public int getMaxWords() {
            return maxWords;
        }

        public void setMaxWords(int maxWords) {
            this.maxWords = maxWords;
        }

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }
}
