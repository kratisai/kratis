package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitProtocolRepoProviderTest {

    @Mock
    private GitCommandRunner commandRunner;

    private GitProtocolRepoProvider provider;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        provider = new GitProtocolRepoProvider(commandRunner);
        testRepo =
                new Repository("test-repo", "https://git.example.com/group/repo.git", "main", RepositoryType.GENERIC);
    }

    @Test
    void supportedType_returnsGeneric() {
        assertThat(provider.supportedType()).isEqualTo(RepositoryType.GENERIC);
    }

    @Test
    void readFile_withPublicRepo_runsGitArchiveWithoutSshCommand() throws Exception {
        when(commandRunner.run(anyList(), any()))
                .thenReturn(new GitCommandRunner.Result(0, "file content".getBytes(StandardCharsets.UTF_8)));

        String result = provider.readFile(testRepo, GitAuthMaterial.none(), "README.md", "main");

        assertThat(result).isEqualTo("file content");
        verify(commandRunner)
                .run(
                        List.of("git", "archive", "--remote=https://git.example.com/group/repo.git", "main:README.md"),
                        null);
    }

    @Test
    void readFile_usesHeadRef_whenBranchBlank() throws Exception {
        when(commandRunner.run(anyList(), any()))
                .thenReturn(new GitCommandRunner.Result(0, "content".getBytes(StandardCharsets.UTF_8)));

        provider.readFile(testRepo, GitAuthMaterial.none(), "README.md", "  ");

        verify(commandRunner)
                .run(
                        List.of("git", "archive", "--remote=https://git.example.com/group/repo.git", "HEAD:README.md"),
                        null);
    }

    @Test
    void readFile_withSshKey_injectsGitSshCommandAndCleansUp() throws Exception {
        when(commandRunner.run(anyList(), any()))
                .thenReturn(new GitCommandRunner.Result(0, "ssh content".getBytes(StandardCharsets.UTF_8)));

        String result = provider.readFile(testRepo, GitAuthMaterial.ofSshKey("pem-private-key"), "README.md", "main");

        assertThat(result).isEqualTo("ssh content");
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> envCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(commandRunner).run(anyList(), envCaptor.capture());
        assertThat(envCaptor.getValue().get("GIT_SSH_COMMAND"))
                .startsWith("ssh -i ")
                .contains("StrictHostKeyChecking=no");
    }

    @Test
    void readFile_nonZeroExitCode_throwsWithOutput() throws Exception {
        when(commandRunner.run(anyList(), any()))
                .thenReturn(new GitCommandRunner.Result(
                        128, "fatal: Repository not found".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.none(), "README.md", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git archive failed")
                .hasMessageContaining("Repository not found");
    }

    @Test
    void readFile_oversizedOutput_throws() throws Exception {
        when(commandRunner.run(anyList(), any())).thenReturn(new GitCommandRunner.Result(0, new byte[1_000_001]));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.none(), "README.md", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void readFile_ioException_wrapsAndPreservesInterrupt() throws Exception {
        when(commandRunner.run(anyList(), any())).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.none(), "README.md", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read file via git protocol");
    }

    @Test
    void readFile_nullUrl_throws() {
        testRepo.setUrl(null);

        assertThatThrownBy(() -> provider.readFile(testRepo, GitAuthMaterial.none(), "README.md", "main"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listRepositories_throwsUnsupported() {
        assertThatThrownBy(() -> provider.listRepositories(null, null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not support listing");
    }

    @Test
    void supportsPullRequests_returnsFalse() {
        assertThat(provider.supportsPullRequests()).isFalse();
    }

    @Test
    void createPullRequest_throwsUnsupportedOperation() {
        CreatePullRequestCommand command = new CreatePullRequestCommand("branch", "main", "Title", "Desc", false);

        assertThatThrownBy(() -> provider.createPullRequest(testRepo, GitAuthMaterial.none(), command))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("do not support upstream Pull Request creation");
    }
}
