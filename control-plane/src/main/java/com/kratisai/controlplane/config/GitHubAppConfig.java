package com.kratisai.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "kratis.github")
public class GitHubAppConfig {

    /** The GitHub App ID (numeric). */
    private String appId;

    /** The GitHub App name/slug used to derive the installation URL. */
    private String appName;

    /** Path to a file containing the private key in PEM format. */
    private String privateKeyPath;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getInstallationUrl() {
        if (!StringUtils.hasText(appName)) {
            throw new IllegalStateException("GitHub App name is not configured");
        }
        return "https://github.com/apps/" + appName + "/installations/new";
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public boolean isEnabled() {
        return StringUtils.hasText(appId) && StringUtils.hasText(appName) && StringUtils.hasText(privateKeyPath);
    }
}
