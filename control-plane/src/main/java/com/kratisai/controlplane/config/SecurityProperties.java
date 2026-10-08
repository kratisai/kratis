package com.kratisai.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Security and CORS configuration.
 *
 * <p>Origins are comma-separated host lists; {@code *} allows any origin.
 */
@Component
@ConfigurationProperties(prefix = "kratis.security")
public class SecurityProperties {

    /** Path to the credential-encryption key; generated and persisted on first start when absent. */
    private String keyFilePath = "./data/encryption.key";

    private String allowedOrigins = "*";

    private String clientAllowedOrigins;

    private String envAllowedOrigins = "*";

    public String getKeyFilePath() {
        return keyFilePath;
    }

    public void setKeyFilePath(String keyFilePath) {
        this.keyFilePath = keyFilePath;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getClientAllowedOrigins() {
        return StringUtils.hasText(clientAllowedOrigins) ? clientAllowedOrigins : allowedOrigins;
    }

    public void setClientAllowedOrigins(String clientAllowedOrigins) {
        this.clientAllowedOrigins = clientAllowedOrigins;
    }

    public String getEnvAllowedOrigins() {
        return StringUtils.hasText(envAllowedOrigins) ? envAllowedOrigins : "*";
    }

    public void setEnvAllowedOrigins(String envAllowedOrigins) {
        this.envAllowedOrigins = envAllowedOrigins;
    }
}
