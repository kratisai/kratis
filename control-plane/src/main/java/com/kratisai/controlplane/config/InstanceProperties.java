package com.kratisai.controlplane.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Identity of this control-plane instance. The id scopes the sandbox resources this instance owns
 * (container and network labels consumed by the zombie collector), so co-located instances never
 * reap each other's sandboxes. The hostname is the host the control plane runs on — used for
 * sandbox-side derived identities (e.g. the git author email of platform commits) — never the
 * sandbox guest hostname.
 */
@Component
@ConfigurationProperties(prefix = "kratis.instance")
public class InstanceProperties {

    private String id = "default";
    private String hostname = "";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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
