package com.kratisai.controlplane.config;

import java.nio.file.Path;
import java.time.Duration;
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
     * Root directory holding each batch's working clone; per-batch subdirectories are created under it.
     */
    private String cloneDir =
            Path.of(System.getProperty("java.io.tmpdir"), "kratis-ingest").toString();

    /**
     * Dimension research specific configuration.
     */
    private DimensionResearch dimensionResearch = new DimensionResearch();

    private Matching matching = new Matching();

    /**
     * SBOM binary configuration used during surface extraction.
     */
    private Sbom sbom = new Sbom();

    private TeamWiki teamWiki = new TeamWiki();

    private Parser parser = new Parser();

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public String getCloneDir() {
        return cloneDir;
    }

    public void setCloneDir(String cloneDir) {
        this.cloneDir = cloneDir;
    }

    public DimensionResearch getDimensionResearch() {
        return dimensionResearch;
    }

    public void setDimensionResearch(DimensionResearch dimensionResearch) {
        this.dimensionResearch = dimensionResearch;
    }

    public Matching getMatching() {
        return matching;
    }

    public void setMatching(Matching matching) {
        this.matching = matching;
    }

    public Sbom getSbom() {
        return sbom;
    }

    public void setSbom(Sbom sbom) {
        this.sbom = sbom;
    }

    public TeamWiki getTeamWiki() {
        return teamWiki;
    }

    public void setTeamWiki(TeamWiki teamWiki) {
        this.teamWiki = teamWiki;
    }

    public Parser getParser() {
        return parser;
    }

    public void setParser(Parser parser) {
        this.parser = parser;
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

    public static class Matching {
        private int maxConcurrency = 16;
        private int maxRetries = 2;
        private long initialBackoffMs = 500;

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
    }

    /** External SBOM binary used during ingestion. */
    public static class Sbom {
        private String syftPath = "../build/bin/syft";

        public String getSyftPath() {
            return syftPath;
        }

        public void setSyftPath(String syftPath) {
            this.syftPath = syftPath;
        }
    }

    /** Configuration properties for automatic and manual Team Wiki rebuilds. */
    public static class TeamWiki {
        private boolean enabled = true;
        private Duration maxDeferral = Duration.ofMinutes(90);
        private int maxBuildsPer24h = 4;
        private Duration pollInterval = Duration.ofSeconds(30);
        private Duration failureBackoff = Duration.ofMinutes(30);
        private int maxIterations = 35;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getMaxDeferral() {
            return maxDeferral;
        }

        public void setMaxDeferral(Duration maxDeferral) {
            this.maxDeferral = maxDeferral;
        }

        public int getMaxBuildsPer24h() {
            return maxBuildsPer24h;
        }

        public void setMaxBuildsPer24h(int maxBuildsPer24h) {
            this.maxBuildsPer24h = maxBuildsPer24h;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getFailureBackoff() {
            return failureBackoff;
        }

        public void setFailureBackoff(Duration failureBackoff) {
            this.failureBackoff = failureBackoff;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }
    }

    /**
     * External parser binaries used during ingestion.
     */
    public static class Parser {

        private String binaryPath;
        private String tempDir;
        private String sccBinaryPath;
        private int workers = 2;
        private int memBudgetMb = 256;

        public String getBinaryPath() {
            return binaryPath;
        }

        public void setBinaryPath(String binaryPath) {
            this.binaryPath = binaryPath;
        }

        public String getTempDir() {
            return tempDir;
        }

        public void setTempDir(String tempDir) {
            this.tempDir = tempDir;
        }

        public String getSccBinaryPath() {
            return sccBinaryPath;
        }

        public void setSccBinaryPath(String sccBinaryPath) {
            this.sccBinaryPath = sccBinaryPath;
        }

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }

        public int getMemBudgetMb() {
            return memBudgetMb;
        }

        public void setMemBudgetMb(int memBudgetMb) {
            this.memBudgetMb = memBudgetMb;
        }
    }
}
