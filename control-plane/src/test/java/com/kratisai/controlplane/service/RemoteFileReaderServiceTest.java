package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.GitHubRepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.RepositoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteFileReaderServiceTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private GitCredentialResolver credentialResolver;

    @Mock
    private RepoProviderRegistry providerRegistry;

    @Mock
    private GitHubRepoProvider gitHubProvider;

    private RemoteFileReaderService remoteFileReaderService;

    private UUID teamId;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        remoteFileReaderService =
                new RemoteFileReaderService(repositoryRepository, credentialResolver, providerRegistry);

        teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);

        testRepo = new Repository();
        testRepo.setId(UUID.randomUUID());
        testRepo.setName("test-repo");
        testRepo.setUrl("https://github.com/test-owner/test-repo.git");
        testRepo.setBranch("main");
        testRepo.setRepositoryType(RepositoryType.GITHUB);
        testRepo.setTeam(team);
    }

    @Test
    void readFile_withPathTraversal_throws() {
        assertThatThrownBy(() -> remoteFileReaderService.readFile(teamId, "test-repo", "../etc/passwd", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Path traversal is not allowed.");
    }

    @Test
    void readFile_withNonexistentRepository_throwsException() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> remoteFileReaderService.readFile(teamId, "nonexistent", "file.txt", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found for team");
    }

    @Test
    void readFile_delegatesToProvider() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));
        when(credentialResolver.resolve(testRepo)).thenReturn(GitAuthMaterial.ofToken("test-token"));
        when(providerRegistry.getProvider(testRepo)).thenReturn(gitHubProvider);
        when(gitHubProvider.readFile(eq(testRepo), any(GitAuthMaterial.class), eq("src/Test.java"), eq("main")))
                .thenReturn("file content");

        String result = remoteFileReaderService.readFile(teamId, "test-repo", "src/Test.java", "main");

        assertThat(result).isEqualTo("file content");
        verify(credentialResolver).resolve(testRepo);
        verify(providerRegistry).getProvider(testRepo);
        verify(gitHubProvider).readFile(eq(testRepo), any(GitAuthMaterial.class), eq("src/Test.java"), eq("main"));
    }

    @Test
    void readFile_withCredentialFailure_throws() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));
        when(credentialResolver.resolve(testRepo)).thenThrow(new IllegalStateException("Auth failed"));

        assertThatThrownBy(() -> remoteFileReaderService.readFile(teamId, "test-repo", "file.txt", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Auth failed");
    }

    @Test
    void readFile_withProviderFailure_throws() {
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));
        when(credentialResolver.resolve(testRepo)).thenReturn(GitAuthMaterial.ofToken("test-token"));
        when(providerRegistry.getProvider(testRepo)).thenReturn(gitHubProvider);
        when(gitHubProvider.readFile(eq(testRepo), any(GitAuthMaterial.class), eq("src/Test.java"), eq("main")))
                .thenThrow(new RuntimeException("GitHub API down"));

        assertThatThrownBy(() -> remoteFileReaderService.readFile(teamId, "test-repo", "src/Test.java", "main"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("GitHub API down");
    }

    @Test
    void readFile_withPublicRepo_delegatesWithNoneAuth() {
        testRepo.setCredential(null);
        when(repositoryRepository.findByTeamIdAndName(teamId, "test-repo")).thenReturn(Optional.of(testRepo));
        when(credentialResolver.resolve(testRepo)).thenReturn(GitAuthMaterial.none());
        when(providerRegistry.getProvider(testRepo)).thenReturn(gitHubProvider);
        when(gitHubProvider.readFile(eq(testRepo), any(GitAuthMaterial.class), eq("README.md"), eq("main")))
                .thenReturn("public content");

        String result = remoteFileReaderService.readFile(teamId, "test-repo", "README.md", "main");

        assertThat(result).isEqualTo("public content");
        verify(credentialResolver).resolve(testRepo);
    }

    @Test
    void buildGitHubContentsUrl_returnsCorrectUri() {
        java.net.URI uri = RemoteFileReaderService.buildGitHubContentsUrl("owner", "repo", "path/file.txt", "main");

        assertThat(uri.toString()).isEqualTo("https://api.github.com/repos/owner/repo/contents/path/file.txt?ref=main");
    }
}
