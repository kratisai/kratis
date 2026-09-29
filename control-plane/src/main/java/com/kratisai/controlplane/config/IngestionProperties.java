package com.kratisai.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the ingestion pipeline.
 */
@Component
@ConfigurationProperties(prefix = "kratis.ingestion")
public class IngestionProperties {

    /**
     * Maximum number of concurrent repository ingestions.
     */
    private int maxConcurrent = 2;

    /**
     * Dimension research specific configuration.
     */
    private DimensionResearch dimensionResearch = new DimensionResearch();

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public DimensionResearch getDimensionResearch() {
        return dimensionResearch;
    }

    public void setDimensionResearch(DimensionResearch dimensionResearch) {
        this.dimensionResearch = dimensionResearch;
    }

    /**
     * Configuration properties specific to dimension research.
     */
    public static class DimensionResearch {
        /**
         * Maximum number of concurrent LLM requests for dimension research.
         */
        private int maxConcurrency = 25;

        /**
         * Maximum number of retries for failed LLM requests.
         */
        private int maxRetries = 3;

        /**
         * Initial backoff time in milliseconds for retry attempts.
         */
        private long initialBackoffMs = 1000;

        /**
         * Maximum backoff time in milliseconds for retry attempts.
         */
        private long maxBackoffMs = 5000;

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }
    }
}
