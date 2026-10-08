package com.kratisai.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kratis.sandbox")
public class SandboxProperties {

    /** URL advertised to user-run connectors (the {@code kratis-connector --mode=daemon} command). */
    private String connectUrl = "ws://localhost:8080/ws/env";

    private final Docker docker = new Docker();
    private final Lifecycle lifecycle = new Lifecycle();
    private final Reconciliation reconciliation = new Reconciliation();

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

    public Reconciliation getReconciliation() {
        return reconciliation;
    }

    public static class Docker {

        /** URL injected into spawned sandbox containers; they reach the host through this name. */
        private String connectUrl = "ws://host.docker.internal:8080/ws/env";

        private String runnerImage = "kratis-runner-base:latest";

        private String registryMirror = "http://host.docker.internal:5001";

        /** Streams the sidecar log into the control-plane log upon teardown. */
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

    /** Sleep and retention policy for sandbox environments. */
    public static class Lifecycle {

        private final Sleep sleep = new Sleep();
        private final Retention retention = new Retention();

        public Sleep getSleep() {
            return sleep;
        }

        public Retention getRetention() {
            return retention;
        }

        /** Auto-suspends a connected sandbox once its latest execution has been idle. */
        public static class Sleep {

            private boolean enabled = true;

            private Duration pollInterval = Duration.ofSeconds(60);

            private Duration idleTimeout = Duration.ofMinutes(20);

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
        }

        /** Terminates sleeping sandboxes whose suspend snapshot has gone unused too long. */
        public static class Retention {

            private boolean enabled = true;

            private Duration pollInterval = Duration.ofDays(1);

            private Duration period = Duration.ofDays(30);

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

            public Duration getPeriod() {
                return period;
            }

            public void setPeriod(Duration period) {
                this.period = period;
            }
        }
    }

    /** Cleanup of sandboxes leaked by crashes, missed heartbeats, or failed provisioning. */
    public static class Reconciliation {

        private boolean enabled = true;

        private Duration pollInterval = Duration.ofSeconds(10);

        private Duration gracePeriod = Duration.ofSeconds(30);

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

        public Duration getGracePeriod() {
            return gracePeriod;
        }

        public void setGracePeriod(Duration gracePeriod) {
            this.gracePeriod = gracePeriod;
        }
    }
}
