package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeGitHubApiClientConfig.FakeGitHubApiClient;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.RepositoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class RemoteFileReaderServiceIntegrationTest {

    @Autowired
    private RemoteFileReaderService remoteFileReaderService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private FakeGitHubApiClient fakeGitHubApiClient;

    private Team team;

    @BeforeEach
    void setUp() {
        fakeGitHubApiClient.reset();
        databaseCleaner.cleanAll();
        team = testDataFactory.createUserAndTeam().team();
    }

    @AfterEach
    void tearDown() {
        fakeGitHubApiClient.reset();
        databaseCleaner.cleanAll();
    }

    @Test
    void readFile_withLazyCredential_resolvesCredentialWithinTransaction() {
        RepoCredential credential = testDataFactory.createCredential(team, "PAT", CredentialType.PAT, "pat-secret");
        saveGitHubRepository("kratis", credential);

        String content = remoteFileReaderService.readFile(team.getId(), "kratis", "src/Main.java", "main");

        assertThat(content).isEqualTo("mock file content");
    }

    @Test
    void readFile_providerFailure_propagatesException() {
        RepoCredential credential = testDataFactory.createCredential(team, "PAT", CredentialType.PAT, "pat-secret");
        saveGitHubRepository("kratis", credential);

        fakeGitHubApiClient.setShouldThrow(true);

        assertThatThrownBy(() -> remoteFileReaderService.readFile(team.getId(), "kratis", "src/Main.java", "main"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated GitHub API failure");
    }

    @Test
    void readFile_unknownRepository_throws() {
        assertThatThrownBy(() -> remoteFileReaderService.readFile(team.getId(), "missing", "src/Main.java", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    private void saveGitHubRepository(String name, RepoCredential credential) {
        Repository repository =
                new Repository(name, "https://github.com/kratisai/kratis.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);
        repository.setCredential(credential);
        repositoryRepository.save(repository);
    }
}
