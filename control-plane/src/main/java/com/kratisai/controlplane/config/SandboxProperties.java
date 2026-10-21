package com.kratisai.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kratis.sandbox")
public class SandboxProperties {

    private String connectUrl = "ws://localhost:8080/ws/env";

    private final Docker docker = new Docker();
    private final Lifecycle lifecycle = new Lifecycle();

    public String getConnectUrl() {
        return connectUrl;
    }

    public void setConnectUrl(String connectUrl) {
        this.connectUrl = connectUrl;
    }

    public Docker getDocker() {
        return docker;
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    public static class Docker {

        private String connectUrl = "ws://host.docker.internal:8080/ws/env";

        private String runnerImage = "kratis-runner-base:latest";

        private String registryMirror = "http://host.docker.internal:5001";

        private boolean debug = false;

        public String getConnectUrl() {
            return connectUrl;
        }

        public void setConnectUrl(String connectUrl) {
            this.connectUrl = connectUrl;
        }

        public String getRunnerImage() {
            return runnerImage;
        }

        public void setRunnerImage(String runnerImage) {
            this.runnerImage = runnerImage;
        }

        public String getRegistryMirror() {
            return registryMirror;
        }

        public void setRegistryMirror(String registryMirror) {
            this.registryMirror = registryMirror;
        }

        public boolean isDebug() {
            return debug;
        }

        public void setDebug(boolean debug) {
            this.debug = debug;
        }
    }

    public static class Lifecycle {

        private boolean enabled = true;

        private Duration pollInterval = Duration.ofSeconds(30);

        private Duration idleTimeout = Duration.ofMinutes(20);

        private Duration connectTimeout = Duration.ofMinutes(5);

        private Duration settleWindow = Duration.ofMinutes(2);

        private Duration startupGrace = Duration.ofSeconds(30);

        private Duration retentionPeriod = Duration.ofDays(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getSettleWindow() {
            return settleWindow;
        }

        public void setSettleWindow(Duration settleWindow) {
            this.settleWindow = settleWindow;
        }

        public Duration getStartupGrace() {
            return startupGrace;
        }

        public void setStartupGrace(Duration startupGrace) {
            this.startupGrace = startupGrace;
        }

        public Duration getRetentionPeriod() {
            return retentionPeriod;
        }

        public void setRetentionPeriod(Duration retentionPeriod) {
            this.retentionPeriod = retentionPeriod;
        }
    }
}
