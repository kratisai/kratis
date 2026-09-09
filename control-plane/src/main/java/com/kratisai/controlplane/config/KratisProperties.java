package com.kratisai.controlplane.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Control-plane host settings. The hostname is the host the control plane runs
 * on — used for sandbox-side derived identities (e.g. the git author email of
 * platform commits) — never the sandbox guest hostname.
 */
@Component
@ConfigurationProperties(prefix = "kratis")
public class KratisProperties {

    private String hostname = "";

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /** Resolves the control-plane hostname, falling back to the environment or local host. */
    public String resolveHostname() {
        if (StringUtils.hasText(hostname)) {
            return hostname.trim();
        }
        String envHost = System.getenv("HOSTNAME");
        if (StringUtils.hasText(envHost)) {
            return envHost.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
