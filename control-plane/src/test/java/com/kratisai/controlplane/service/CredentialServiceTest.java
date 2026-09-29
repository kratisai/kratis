package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class CredentialServiceTest {

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldEncryptAndDecryptSecretCorrectly() {
        String originalSecret = "my-super-secret-pat-token-123456789";

        String encrypted = credentialService.encrypt(originalSecret);
        assertThat(encrypted).isNotNull();
        assertThat(encrypted).isNotEqualTo(originalSecret);

        String decrypted = credentialService.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(originalSecret);
    }

    @Test
    void encrypt_nullInput_shouldReturnNull() {
        assertThat(credentialService.encrypt(null)).isNull();
    }

    @Test
    void decrypt_nullInput_shouldReturnNull() {
        assertThat(credentialService.decrypt(null)).isNull();
    }

    @Test
    void decryptSecret_nullCredential_shouldReturnNull() {
        assertThat(credentialService.decryptSecret(null)).isNull();
    }

    @Test
    void shouldSaveAndRetrieveCredentialForTeam() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        String token = "ghp_TestTokenForGitHubAppOnboarding";
        RepoCredential cred = credentialService.saveCredential(
                team.getId(),
                "GitHub PAT",
                CredentialType.GITHUB_APP,
                token,
                "ssh-rsa AAA...",
                "{\"username\":\"dev-user\"}");

        assertThat(cred.getId()).isNotNull();
        assertThat(cred.getName()).isEqualTo("GitHub PAT");
        assertThat(cred.getType()).isEqualTo(CredentialType.GITHUB_APP);
        assertThat(cred.getPublicKey()).isEqualTo("ssh-rsa AAA...");
        assertThat(cred.getProviderMetadata()).contains("dev-user");

        // Verify decrypted matches
        String decrypted = credentialService.decryptSecret(cred);
        assertThat(decrypted).isEqualTo(token);

        // Fetch list
        List<RepoCredential> list = credentialService.listCredentials(team.getId());
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().getId()).isEqualTo(cred.getId());

        // Fetch single
        Optional<RepoCredential> single = credentialService.getCredential(team.getId(), cred.getId());
        assertThat(single).isPresent();
    }

    @Test
    void shouldUpdateCredentialSuccessfully() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        RepoCredential cred = credentialService.saveCredential(
                team.getId(), "Initial Name", CredentialType.SSH_KEY, "initial-private-key", "initial-pubkey", "{}");

        RepoCredential updated = credentialService.updateCredential(
                team.getId(),
                cred.getId(),
                "Updated Name",
                "updated-private-key",
                "updated-pubkey",
                "{\"updated\":true}");

        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(credentialService.decryptSecret(updated)).isEqualTo("updated-private-key");
        assertThat(updated.getPublicKey()).isEqualTo("updated-pubkey");
        assertThat(updated.getProviderMetadata()).contains("updated");
    }

    @Test
    void updateCredential_withNullSecret_shouldKeepExistingSecret() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        RepoCredential cred = credentialService.saveCredential(
                team.getId(), "Name", CredentialType.SSH_KEY, "original-key", "pubkey", "{}");

        RepoCredential updated =
                credentialService.updateCredential(team.getId(), cred.getId(), "New Name", null, null, null);

        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(credentialService.decryptSecret(updated)).isEqualTo("original-key");
    }

    @Test
    void updateCredential_withEmptySecret_shouldKeepExistingSecret() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        RepoCredential cred = credentialService.saveCredential(
                team.getId(), "Name", CredentialType.SSH_KEY, "original-key", "pubkey", "{}");

        RepoCredential updated =
                credentialService.updateCredential(team.getId(), cred.getId(), "New Name", "  ", null, null);

        assertThat(credentialService.decryptSecret(updated)).isEqualTo("original-key");
    }

    @Test
    void updateCredential_nonExistent_shouldThrow() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        assertThatThrownBy(() -> credentialService.updateCredential(
                        team.getId(), UUID.randomUUID(), "Name", "secret", "pub", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credential not found");
    }

    @Test
    void saveCredential_nonExistentTeam_shouldThrow() {
        assertThatThrownBy(() -> credentialService.saveCredential(
                        UUID.randomUUID(), "Name", CredentialType.GITHUB_APP, "secret", "pub", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Team not found");
    }

    @Test
    void deleteCredential_shouldRemoveCredential() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        RepoCredential cred = credentialService.saveCredential(
                team.getId(), "ToDelete", CredentialType.GITHUB_APP, "token", "pub", "{}");

        credentialService.deleteCredential(team.getId(), cred.getId());

        assertThat(credentialService.getCredential(team.getId(), cred.getId())).isEmpty();
    }

    @Test
    void deleteCredential_withAssociatedRepos_shouldCascadeDelete() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        RepoCredential cred = credentialService.saveCredential(
                team.getId(), "WithRepos", CredentialType.GITHUB_APP, "token", "pub", "{}");

        credentialService.deleteCredential(team.getId(), cred.getId());

        assertThat(credentialService.getCredential(team.getId(), cred.getId())).isEmpty();
    }

    @Test
    void deleteCredential_nonExistent_shouldThrow() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        assertThatThrownBy(() -> credentialService.deleteCredential(team.getId(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credential not found");
    }

    @Test
    void findRepositoriesUsingCredential_shouldReturnAssociatedRepos() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        RepoCredential cred = credentialService.saveCredential(
                team.getId(), "WithRepos", CredentialType.GITHUB_APP, "token", "pub", "{}");

        testDataFactory.createRepository(team, "repo1", "https://github.com/test/repo1", "main", cred);
        testDataFactory.createRepository(team, "repo2", "https://github.com/test/repo2", "main", cred);

        List<Repository> repos = credentialService.findRepositoriesUsingCredential(team.getId(), cred.getId());
        assertThat(repos).hasSize(2);
    }

    @Test
    void findRepositoriesUsingCredential_nonExistent_shouldThrow() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        assertThatThrownBy(() -> credentialService.findRepositoriesUsingCredential(team.getId(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credential not found");
    }

    @Test
    void getCredential_nonExistent_shouldReturnEmpty() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();

        assertThat(credentialService.getCredential(team.getId(), UUID.randomUUID()))
                .isEmpty();
    }
}
