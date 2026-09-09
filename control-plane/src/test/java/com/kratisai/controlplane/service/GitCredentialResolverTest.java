package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.*;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class GitCredentialResolverTest {

    @Autowired
    private GitCredentialResolver resolver;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private TestDataFactory testDataFactory;

    private Team team;

    @BeforeEach
    void setUp() {
        var ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();
    }

    @Test
    void resolve_nullCredential_shouldReturnNone() {
        Repository repo =
                new Repository("public-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repo.setTeam(team);
        GitAuthMaterial auth = resolver.resolve(repo);
        assertThat(auth).isEqualTo(GitAuthMaterial.none());
        assertThat(auth.maybeToken()).isEmpty();
        assertThat(auth.maybeSshKey()).isEmpty();
    }

    @Test
    void resolve_patCredential_shouldReturnToken() {
        RepoCredential cred =
                new RepoCredential(team, "PAT Cred", CredentialType.PAT, credentialService.encrypt("pat-secret"));
        Repository repo = new Repository("repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repo.setCredential(cred);
        repo.setTeam(team);

        GitAuthMaterial auth = resolver.resolve(repo);
        assertThat(auth.maybeToken()).hasValue("pat-secret");
        assertThat(auth.maybeSshKey()).isEmpty();
    }

    @Test
    void resolve_sshKeyCredential_shouldReturnSshKey() {
        RepoCredential cred =
                new RepoCredential(team, "SSH Cred", CredentialType.SSH_KEY, credentialService.encrypt("ssh-pem"));
        Repository repo = new Repository("repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setCredential(cred);
        repo.setTeam(team);

        GitAuthMaterial auth = resolver.resolve(repo);
        assertThat(auth.maybeSshKey()).hasValue("ssh-pem");
        assertThat(auth.maybeToken()).isEmpty();
    }

    @Test
    void resolve_githubAppCredential_withInstallationId_shouldExchangeToken() {
        RepoCredential cred = new RepoCredential(team, "App Cred", CredentialType.GITHUB_APP, "fake-jwt");
        cred.setProviderMetadata("{\"installationId\":\"12345\"}");
        Repository repo = new Repository("repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repo.setCredential(cred);
        repo.setTeam(team);

        GitAuthMaterial auth = resolver.resolve(repo);
        assertThat(auth.maybeToken()).isPresent();
        assertThat(auth.maybeToken().get()).isEqualTo("ghs_faketoken");
    }

    @Test
    void resolve_githubAppCredential_missingInstallationId_shouldThrow() {
        RepoCredential cred = new RepoCredential(team, "App Cred", CredentialType.GITHUB_APP, "fake-jwt");
        Repository repo = new Repository("repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repo.setCredential(cred);
        repo.setTeam(team);

        assertThatThrownBy(() -> resolver.resolve(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installationId");
    }

    @Test
    void resolve_patCredential_emptyDecrypted_shouldThrow() {
        RepoCredential cred = new RepoCredential(team, "PAT Cred", CredentialType.PAT, credentialService.encrypt(""));
        Repository repo = new Repository("repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repo.setCredential(cred);
        repo.setTeam(team);

        assertThatThrownBy(() -> resolver.resolve(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAT");
    }

    @Test
    void resolve_sshKeyCredential_emptyDecrypted_shouldThrow() {
        RepoCredential cred =
                new RepoCredential(team, "SSH Cred", CredentialType.SSH_KEY, credentialService.encrypt(""));
        Repository repo = new Repository("repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setCredential(cred);
        repo.setTeam(team);

        assertThatThrownBy(() -> resolver.resolve(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSH_KEY");
    }
}
