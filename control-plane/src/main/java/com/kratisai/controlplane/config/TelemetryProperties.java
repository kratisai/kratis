package com.kratisai.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kratis.telemetry")
public class TelemetryProperties {

    private static final String DEFAULT_POSTHOG_ENDPOINT = "https://us.i.posthog.com/capture/";

    private String disabled = "false";
    private String doNotTrack = "false";
    private String version = "unknown";
    private String buildTag = "unknown";
    private String endpoint = DEFAULT_POSTHOG_ENDPOINT;
    private Duration initialDelay = Duration.ofMinutes(1);
    private Duration fixedDelay = Duration.ofDays(1);
    private Duration httpTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return !flag(disabled) && !flag(doNotTrack);
    }

    private static boolean flag(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "1".equals(trimmed) || Boolean.parseBoolean(trimmed);
    }

    public String getDisabled() {
        return disabled;
    }

    public void setDisabled(String disabled) {
        this.disabled = disabled;
    }

    public String getDoNotTrack() {
        return doNotTrack;
    }

    public void setDoNotTrack(String doNotTrack) {
        this.doNotTrack = doNotTrack;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getBuildTag() {
        return buildTag;
    }

    public void setBuildTag(String buildTag) {
        this.buildTag = buildTag;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public void setHttpTimeout(Duration httpTimeout) {
        this.httpTimeout = httpTimeout;
    }
}
