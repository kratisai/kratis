package com.kratisai.controlplane.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT issuing configuration. The HMAC secret must be at least 256 bits; access and refresh token
 * lifetimes are separate so a stolen access token cannot be refreshed indefinitely.
 */
@Component
@ConfigurationProperties(prefix = "kratis.auth.jwt")
public class AuthProperties {

    private String secret;

    private Duration expiration = Duration.ofHours(1);

    private Duration refreshExpiration = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getExpiration() {
        return expiration;
    }

    public void setExpiration(Duration expiration) {
        this.expiration = expiration;
    }

    public Duration getRefreshExpiration() {
        return refreshExpiration;
    }

    public void setRefreshExpiration(Duration refreshExpiration) {
        this.refreshExpiration = refreshExpiration;
    }
}
