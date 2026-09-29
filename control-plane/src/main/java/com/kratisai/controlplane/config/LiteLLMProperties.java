package com.kratisai.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "kratis.litellm")
public class LiteLLMProperties {

    /** URL of the LiteLLM proxy as reachable from the host. */
    private String baseUrl;

    /** URL of the LiteLLM proxy as reachable from inside sandbox containers. */
    private String sandboxBaseUrl;

    private String masterKey = "sk-litellm-master";

    private Duration usageRefreshInterval = Duration.ofSeconds(15);

    /** Pause before the final usage fetch at execution completion, giving LiteLLM's async spend-log flush time to land. */
    private Duration usageFinalizeDelay = Duration.ofSeconds(3);

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(30);

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSandboxBaseUrl() {
        return StringUtils.hasText(sandboxBaseUrl) ? sandboxBaseUrl : baseUrl;
    }

    public void setSandboxBaseUrl(String sandboxBaseUrl) {
        this.sandboxBaseUrl = sandboxBaseUrl;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public Duration getUsageRefreshInterval() {
        return usageRefreshInterval;
    }

    public void setUsageRefreshInterval(Duration usageRefreshInterval) {
        this.usageRefreshInterval = usageRefreshInterval;
    }

    public Duration getUsageFinalizeDelay() {
        return usageFinalizeDelay;
    }

    public void setUsageFinalizeDelay(Duration usageFinalizeDelay) {
        this.usageFinalizeDelay = usageFinalizeDelay;
    }
}
