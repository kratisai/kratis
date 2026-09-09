package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.git.GitTransportUrlBuilder;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.service.GitCredentialResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class GitCloneServiceTest {

    @Autowired
    private TestDataFactory testDataFactory;

    private String dummyRepoUrl;

    private GitCloneService gitCloneService;

    private final GitCredentialResolver credentialResolver = mock(GitCredentialResolver.class);
    private final IngestionBatchLogService.BatchLogger batchLogger = mock(IngestionBatchLogService.BatchLogger.class);

    @Autowired
    private GitTransportUrlBuilder transportUrlBuilder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        gitCloneService = new GitCloneService(tempDir.toString(), credentialResolver, transportUrlBuilder);

        // Default: public repositories resolve to no auth
        lenient().when(credentialResolver.resolve(any(Repository.class))).thenReturn(GitAuthMaterial.none());

        Path sourceRepoDir = tempDir.resolve("dummy-repo-source");
        Files.createDirectories(sourceRepoDir);

        new ProcessBuilder("git", "init")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();

        Path readme = sourceRepoDir.resolve("readme.md");
        Files.writeString(readme, "# Dummy Repo");

        new ProcessBuilder("git", "add", "readme.md")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();

        new ProcessBuilder(
                        "git",
                        "-c",
                        "user.name=Test",
                        "-c",
                        "user.email=test@example.com",
                        "commit",
                        "-m",
                        "Initial commit")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();

        new ProcessBuilder("git", "branch", "-M", "main")
                .directory(sourceRepoDir.toFile())
                .start()
                .waitFor();

        dummyRepoUrl = "file://" + sourceRepoDir.toAbsolutePath();
    }

    @Test
    void shouldCleanUpDirectoryCorrectly() throws IOException {
        Path testSubdir = tempDir.resolve("test-sub");
        Files.createDirectories(testSubdir);
        Path testFile = testSubdir.resolve("file.txt");
        Files.writeString(testFile, "hello");

        assertThat(Files.exists(testFile)).isTrue();

        gitCloneService.cleanupDirectory(testSubdir);

        assertThat(Files.exists(testSubdir)).isFalse();
    }

    @Test
    void shouldFailCloningWithInvalidUrl() {
        Repository repository = testDataFactory
                .createContextWithCustomRepo("Test Team", "invalid-repo", "https://localhost:9999/non-existent.git")
                .repository();

        UUID batchId = UUID.randomUUID();

        // Clones to an invalid port/url should fail and throw an IOException
        assertThatThrownBy(() -> gitCloneService.cloneRepository(repository, batchId, batchLogger))
                .isInstanceOf(IOException.class);

        // Ensure target folder was cleaned up on failure
        Path targetPath = tempDir.resolve(batchId.toString());
        assertThat(Files.exists(targetPath)).isFalse();
    }

    @Test
    void shouldCloneRepositorySuccessfully() throws Exception {
        Repository repository = testDataFactory
                .createContextWithCustomRepo("Test Team", "dummy-repo", dummyRepoUrl)
                .repository();

        UUID batchId = UUID.randomUUID();

        GitCloneService.CloneResult result = gitCloneService.cloneRepository(repository, batchId, batchLogger);

        assertThat(result).isNotNull();
        assertThat(result.cloneDirectory()).exists();
        assertThat(result.cloneDirectory().resolve("readme.md")).exists();
        assertThat(result.commitHash()).isNotBlank().isNotEqualTo("UNKNOWN");

        // Clean up
        gitCloneService.cleanupDirectory(result.cloneDirectory());
    }

    @Test
    void shouldCloneRepositoryWithBasicAuthOrPat() throws Exception {
        Repository repository = testDataFactory
                .createContextWithCustomRepo("Test Team", "dummy-repo", dummyRepoUrl)
                .repository();

        when(credentialResolver.resolve(repository)).thenReturn(GitAuthMaterial.ofToken("fake-token"));

        UUID batchId = UUID.randomUUID();
        GitCloneService.CloneResult result = gitCloneService.cloneRepository(repository, batchId, batchLogger);

        assertThat(result).isNotNull();
        assertThat(result.cloneDirectory().resolve("readme.md")).exists();

        // Clean up
        gitCloneService.cleanupDirectory(result.cloneDirectory());
    }

    @Test
    void shouldCloneRepositoryWithSshKey() throws Exception {
        Repository repository = testDataFactory
                .createContextWithCustomRepo("Test Team", "dummy-repo", dummyRepoUrl)
                .repository();

        when(credentialResolver.resolve(repository)).thenReturn(GitAuthMaterial.ofSshKey("fake-private-key-content"));

        UUID batchId = UUID.randomUUID();
        GitCloneService.CloneResult result = gitCloneService.cloneRepository(repository, batchId, batchLogger);

        assertThat(result).isNotNull();
        assertThat(result.cloneDirectory().resolve("readme.md")).exists();

        // Clean up
        gitCloneService.cleanupDirectory(result.cloneDirectory());
    }

    @Test
    void shouldCloneRepositoryWithGitHubApp() throws Exception {
        Repository repository = testDataFactory
                .createContextWithCustomRepo("Test Team", "dummy-repo", dummyRepoUrl)
                .repository();

        when(credentialResolver.resolve(repository)).thenReturn(GitAuthMaterial.ofToken("ghs_faketoken"));

        UUID batchId = UUID.randomUUID();

        GitCloneService.CloneResult result = gitCloneService.cloneRepository(repository, batchId, batchLogger);

        assertThat(result).isNotNull();
        assertThat(result.cloneDirectory().resolve("readme.md")).exists();

        // Clean up
        gitCloneService.cleanupDirectory(result.cloneDirectory());
    }

    @Test
    void shouldCleanUpExistingDirectoryBeforeCloning() throws Exception {
        Repository repository = testDataFactory
                .createContextWithCustomRepo("Test Team", "dummy-repo", dummyRepoUrl)
                .repository();

        UUID batchId = UUID.randomUUID();
        Path targetPath = tempDir.resolve(batchId.toString());

        // Simulate an existing directory from a previous failed run
        Files.createDirectories(targetPath);
        Path dummyFile = targetPath.resolve("dummy.txt");
        Files.writeString(dummyFile, "leftover data");

        assertThat(Files.exists(dummyFile)).isTrue();

        // Clone should succeed and clean up the existing directory first
        GitCloneService.CloneResult result = gitCloneService.cloneRepository(repository, batchId, batchLogger);

        assertThat(result).isNotNull();
        assertThat(result.cloneDirectory()).exists();
        assertThat(result.cloneDirectory().resolve("readme.md")).exists();
        // The leftover file should be gone
        assertThat(Files.exists(dummyFile)).isFalse();

        // Clean up
        gitCloneService.cleanupDirectory(result.cloneDirectory());
    }
}
