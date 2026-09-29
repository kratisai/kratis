package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.*;

import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepoProviderRegistryTest {

    private final GitHubRepoProvider gitHubProvider = new GitHubRepoProvider(null);
    private final GitLabRepoProvider gitLabProvider = new GitLabRepoProvider(null);
    private final BitbucketRepoProvider bitbucketProvider = new BitbucketRepoProvider(null);
    private final AzureRepoProvider azureProvider = new AzureRepoProvider(null);
    private final GitProtocolRepoProvider gitProtocolProvider = new GitProtocolRepoProvider(null);

    private static Repository repoWithToken(RepositoryType type) {
        Team team = new Team("t", "d");
        Repository repo = new Repository("r", "https://example.com/repo.git", "main", type);
        repo.setTeam(team);
        repo.setCredential(new RepoCredential(team, "pat", CredentialType.PAT, "token"));
        return repo;
    }

    private static Repository repoWithoutCredential(RepositoryType type) {
        Team team = new Team("t", "d");
        Repository repo = new Repository("r", "https://example.com/repo.git", "main", type);
        repo.setTeam(team);
        return repo;
    }

    @Test
    void getProvider_githubRepoWithToken_returnsGitHubProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider, gitProtocolProvider));

        assertThat(registry.getProvider(repoWithToken(RepositoryType.GITHUB))).isEqualTo(gitHubProvider);
    }

    @Test
    void getProvider_githubRepoWithGitHubApp_returnsGitHubProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider, gitProtocolProvider));

        Repository repo = repoWithoutCredential(RepositoryType.GITHUB);
        repo.setCredential(new RepoCredential(repo.getTeam(), "app", CredentialType.GITHUB_APP, "{}"));

        assertThat(registry.getProvider(repo)).isEqualTo(gitHubProvider);
    }

    @Test
    void getProvider_gitlabRepoWithToken_returnsGitLabProvider() {
        RepoProviderRegistry registry =
                new RepoProviderRegistry(List.of(gitHubProvider, gitLabProvider, gitProtocolProvider));

        assertThat(registry.getProvider(repoWithToken(RepositoryType.GITLAB))).isEqualTo(gitLabProvider);
    }

    @Test
    void getProvider_bitbucketRepoWithToken_returnsBitbucketProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(bitbucketProvider, gitProtocolProvider));

        assertThat(registry.getProvider(repoWithToken(RepositoryType.BITBUCKET)))
                .isEqualTo(bitbucketProvider);
    }

    @Test
    void getProvider_azureRepoWithToken_returnsAzureProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(azureProvider, gitProtocolProvider));

        assertThat(registry.getProvider(repoWithToken(RepositoryType.AZURE))).isEqualTo(azureProvider);
    }

    @Test
    void getProviderByType_returnsRegisteredProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(
                List.of(gitHubProvider, gitLabProvider, bitbucketProvider, azureProvider, gitProtocolProvider));

        assertThat(registry.getProvider(RepositoryType.GITHUB)).isEqualTo(gitHubProvider);
        assertThat(registry.getProvider(RepositoryType.GITLAB)).isEqualTo(gitLabProvider);
        assertThat(registry.getProvider(RepositoryType.BITBUCKET)).isEqualTo(bitbucketProvider);
        assertThat(registry.getProvider(RepositoryType.AZURE)).isEqualTo(azureProvider);
        assertThat(registry.getProvider(RepositoryType.GENERIC)).isEqualTo(gitProtocolProvider);
    }

    @Test
    void getProvider_genericRepo_returnsGitProtocolProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider, gitProtocolProvider));

        assertThat(registry.getProvider(repoWithToken(RepositoryType.GENERIC))).isEqualTo(gitProtocolProvider);
    }

    @Test
    void getProvider_unauthenticatedRepo_returnsGitProtocolProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider, gitProtocolProvider));

        assertThat(registry.getProvider(repoWithoutCredential(RepositoryType.GITHUB)))
                .isEqualTo(gitProtocolProvider);
    }

    @Test
    void getProvider_githubRepoWithSshKeyCredential_returnsGitProtocolProvider() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider, gitProtocolProvider));

        Repository repo = repoWithoutCredential(RepositoryType.GITHUB);
        repo.setCredential(new RepoCredential(repo.getTeam(), "ssh", CredentialType.SSH_KEY, "pem"));

        assertThat(registry.getProvider(repo)).isEqualTo(gitProtocolProvider);
    }

    @Test
    void getProvider_unregisteredType_throws() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider, gitProtocolProvider));

        assertThatThrownBy(() -> registry.getProvider(repoWithToken(RepositoryType.AZURE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AZURE");
    }

    @Test
    void getProvider_sshKeyButNoGitProtocolProvider_fallsThrough() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider));

        Repository repo = repoWithoutCredential(RepositoryType.GITHUB);
        repo.setCredential(new RepoCredential(repo.getTeam(), "ssh", CredentialType.SSH_KEY, "pem"));

        assertThat(registry.getProvider(repo)).isEqualTo(gitHubProvider);
    }

    @Test
    void getProvider_unauthenticatedButNoGitProtocolProvider_fallsThrough() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of(gitHubProvider));

        Repository repo = repoWithoutCredential(RepositoryType.GITHUB);

        assertThat(registry.getProvider(repo)).isEqualTo(gitHubProvider);
    }

    @Test
    void constructor_duplicateSupportedType_throws() {
        assertThatThrownBy(() -> new RepoProviderRegistry(List.of(gitHubProvider, new GitHubRepoProvider(null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void constructor_emptyList_isAllowed() {
        RepoProviderRegistry registry = new RepoProviderRegistry(List.of());

        assertThat(registry.supportedTypes()).isEmpty();
    }

    @Test
    void constructor_nullList_isAllowed() {
        RepoProviderRegistry registry = new RepoProviderRegistry(null);

        assertThat(registry.supportedTypes()).isEmpty();
    }
}
