package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepoCredentialTest {

    @Test
    void defaultConstructor_shouldInitializeEmptyObject() {
        RepoCredential credential = new RepoCredential();
        assertThat(credential.getId()).isNull();
        assertThat(credential.getTeam()).isNull();
        assertThat(credential.getName()).isNull();
        assertThat(credential.getType()).isNull();
        assertThat(credential.getEncryptedSecret()).isNull();
        assertThat(credential.getPublicKey()).isNull();
        assertThat(credential.getProviderMetadata()).isNull();
        assertThat(credential.getCreatedAt()).isNull();
        assertThat(credential.getUpdatedAt()).isNull();
    }

    @Test
    void parameterConstructor_shouldSetFieldsCorrectly() {
        Team team = new Team();
        RepoCredential credential =
                new RepoCredential(team, "My Credential", CredentialType.GITHUB_APP, "secret-encrypted");

        assertThat(credential.getTeam()).isEqualTo(team);
        assertThat(credential.getName()).isEqualTo("My Credential");
        assertThat(credential.getType()).isEqualTo(CredentialType.GITHUB_APP);
        assertThat(credential.getEncryptedSecret()).isEqualTo("secret-encrypted");
    }

    @Test
    void gettersAndSetters_shouldWorkForAllFields() {
        RepoCredential credential = new RepoCredential();

        UUID id = UUID.randomUUID();
        Team team = new Team();
        Instant now = Instant.now();

        credential.setId(id);
        credential.setTeam(team);
        credential.setName("Test Cred");
        credential.setType(CredentialType.PAT);
        credential.setEncryptedSecret("encrypted");
        credential.setPublicKey("public-key");
        credential.setProviderMetadata("{\"foo\":\"bar\"}");
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);

        assertThat(credential.getId()).isEqualTo(id);
        assertThat(credential.getTeam()).isEqualTo(team);
        assertThat(credential.getName()).isEqualTo("Test Cred");
        assertThat(credential.getType()).isEqualTo(CredentialType.PAT);
        assertThat(credential.getEncryptedSecret()).isEqualTo("encrypted");
        assertThat(credential.getPublicKey()).isEqualTo("public-key");
        assertThat(credential.getProviderMetadata()).isEqualTo("{\"foo\":\"bar\"}");
        assertThat(credential.getCreatedAt()).isEqualTo(now);
        assertThat(credential.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onCreate_shouldInitializeTimestamps() {
        RepoCredential credential = new RepoCredential();
        assertThat(credential.getCreatedAt()).isNull();
        assertThat(credential.getUpdatedAt()).isNull();

        credential.onCreate();

        assertThat(credential.getCreatedAt()).isNotNull();
        assertThat(credential.getUpdatedAt()).isNotNull();
        assertThat(credential.getCreatedAt()).isEqualTo(credential.getUpdatedAt());
    }

    @Test
    void onCreate_withExistingCreatedAt_shouldOnlySetUpdatedAt() {
        RepoCredential credential = new RepoCredential();
        Instant customCreatedAt = Instant.now().minusSeconds(100);
        credential.setCreatedAt(customCreatedAt);

        credential.onCreate();

        assertThat(credential.getCreatedAt()).isEqualTo(customCreatedAt);
        assertThat(credential.getUpdatedAt()).isNotNull();
        assertThat(credential.getUpdatedAt()).isAfter(customCreatedAt);
    }

    @Test
    void onUpdate_shouldUpdateTimestampOnly() {
        RepoCredential credential = new RepoCredential();
        Instant originalTime = Instant.now().minusSeconds(100);
        credential.setCreatedAt(originalTime);
        credential.setUpdatedAt(originalTime);

        credential.onUpdate();

        assertThat(credential.getCreatedAt()).isEqualTo(originalTime);
        assertThat(credential.getUpdatedAt()).isAfter(originalTime);
    }

    @Test
    void getMetadata_shouldReturnProviderMetadataValueObject() {
        RepoCredential credential = new RepoCredential();
        credential.setProviderMetadata("{\"installationId\":\"999\"}");

        ProviderMetadata metadata = credential.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.getInstallationId()).contains("999");
    }
}
