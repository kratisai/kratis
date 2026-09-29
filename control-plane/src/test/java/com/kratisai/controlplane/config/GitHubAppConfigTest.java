package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GitHubAppConfigTest {

    private GitHubAppConfig configuredApp() {
        GitHubAppConfig config = new GitHubAppConfig();
        config.setAppId("123456");
        config.setAppName("kratis-app");
        config.setPrivateKeyPath("/data/gh-app-private-key.pem");
        return config;
    }

    @Test
    void isEnabled_trueWhenAllFieldsConfigured() {
        assertThat(configuredApp().isEnabled()).isTrue();
    }

    @Test
    void isEnabled_falseWhenAppIdMissing() {
        GitHubAppConfig config = configuredApp();
        config.setAppId(null);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseWhenAppIdBlank() {
        GitHubAppConfig config = configuredApp();
        config.setAppId("  ");
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseWhenAppNameMissing() {
        GitHubAppConfig config = configuredApp();
        config.setAppName(null);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseWhenPrivateKeyPathMissing() {
        GitHubAppConfig config = configuredApp();
        config.setPrivateKeyPath("");
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void getInstallationUrl_usesAppNameSlug() {
        assertThat(configuredApp().getInstallationUrl())
                .isEqualTo("https://github.com/apps/kratis-app/installations/new");
    }

    @Test
    void getInstallationUrl_throwsWhenAppNameMissing() {
        GitHubAppConfig config = configuredApp();
        config.setAppName(null);
        assertThatThrownBy(config::getInstallationUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub App name is not configured");
    }
}
