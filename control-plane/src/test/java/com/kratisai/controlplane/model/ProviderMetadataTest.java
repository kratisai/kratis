package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderMetadataTest {

    @Test
    void of_withNullOrEmpty_shouldReturnEmptyMetadata() {
        ProviderMetadata nullMetadata = ProviderMetadata.of(null);
        assertThat(nullMetadata.rawJson()).isEmpty();
        assertThat(nullMetadata.getInstallationId()).isEmpty();
        assertThat(nullMetadata.getGitLabUrl()).isEmpty();
        assertThat(nullMetadata.isGitHubApp()).isFalse();

        ProviderMetadata emptyMetadata = ProviderMetadata.of("");
        assertThat(emptyMetadata.rawJson()).isEmpty();
        assertThat(emptyMetadata.getInstallationId()).isEmpty();
        assertThat(emptyMetadata.getGitLabUrl()).isEmpty();
        assertThat(emptyMetadata.isGitHubApp()).isFalse();

        ProviderMetadata whitespaceMetadata = ProviderMetadata.of("   ");
        assertThat(whitespaceMetadata.rawJson()).isEmpty();
        assertThat(whitespaceMetadata.getInstallationId()).isEmpty();
        assertThat(whitespaceMetadata.getGitLabUrl()).isEmpty();
        assertThat(whitespaceMetadata.isGitHubApp()).isFalse();
    }

    @Test
    void of_withInvalidJson_shouldReturnEmptyValues() {
        ProviderMetadata metadata = ProviderMetadata.of("{invalid-json}");
        assertThat(metadata.getInstallationId()).isEmpty();
        assertThat(metadata.getGitLabUrl()).isEmpty();
        assertThat(metadata.isGitHubApp()).isFalse();
    }

    @Test
    void getInstallationId_withValidAndInvalidValues() {
        ProviderMetadata metadataWithId = ProviderMetadata.of("{\"installationId\":\"12345\"}");
        assertThat(metadataWithId.getInstallationId()).contains("12345");
        assertThat(metadataWithId.isGitHubApp()).isTrue();

        ProviderMetadata metadataWithEmptyId = ProviderMetadata.of("{\"installationId\":\"\"}");
        assertThat(metadataWithEmptyId.getInstallationId()).isEmpty();
        assertThat(metadataWithEmptyId.isGitHubApp()).isFalse();

        ProviderMetadata metadataWithWhitespaceId = ProviderMetadata.of("{\"installationId\":\"  \"}");
        assertThat(metadataWithWhitespaceId.getInstallationId()).isEmpty();
        assertThat(metadataWithWhitespaceId.isGitHubApp()).isFalse();

        ProviderMetadata metadataWithoutId = ProviderMetadata.of("{\"provider\":\"github\"}");
        assertThat(metadataWithoutId.getInstallationId()).isEmpty();
        assertThat(metadataWithoutId.isGitHubApp()).isFalse();
    }

    @Test
    void getGitLabUrl_withValidAndInvalidValues() {
        ProviderMetadata metadataWithUrl = ProviderMetadata.of("{\"gitlabUrl\":\"https://gitlab.example.com\"}");
        assertThat(metadataWithUrl.getGitLabUrl()).contains("https://gitlab.example.com");

        ProviderMetadata metadataWithEmptyUrl = ProviderMetadata.of("{\"gitlabUrl\":\"\"}");
        assertThat(metadataWithEmptyUrl.getGitLabUrl()).isEmpty();

        ProviderMetadata metadataWithoutUrl = ProviderMetadata.of("{\"provider\":\"gitlab\"}");
        assertThat(metadataWithoutUrl.getGitLabUrl()).isEmpty();
    }
}
