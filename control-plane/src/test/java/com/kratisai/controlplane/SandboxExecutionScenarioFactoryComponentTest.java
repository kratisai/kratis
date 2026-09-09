package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class SandboxExecutionScenarioFactoryComponentTest {

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionScenarioFactory scenarioFactory;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void startOnConnector_createsExecutionBoundToRepository() {
        TestDataFactory.AuthContext ctx = testDataFactory.createAuthenticatedContext();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "Scenario Chat");

        SandboxExecutionScenarioFactory.ExecutionScenario scenario =
                scenarioFactory.startOnConnector(ctx, chat, "Scenario Connector", "plan-doc", "scenario-repo");

        assertThat(scenario.envId()).isNotNull();
        assertThat(scenario.connectorToken()).isNotBlank();
        assertThat(scenario.canvasDocumentId()).isEqualTo("plan-doc");
        assertThat(scenario.repository()).isNotNull();
        assertThat(scenario.repository().getName()).isEqualTo("scenario-repo");

        SandboxExecution saved =
                sandboxExecutionRepository.findById(scenario.executionId()).orElseThrow();
        assertThat(saved.getRepository().getId())
                .isEqualTo(scenario.repository().getId());
        assertThat(saved.getNewRepoName()).isNull();
        assertThat(saved.getModelName()).isEqualTo(TestDataFactory.DEFAULT_CHAT_MODEL);
        assertThat(saved.getModelProvider().getId()).isEqualTo(ctx.provider().getId());
    }

    @Test
    void startOnConnector_usesExistingRepository() {
        TestDataFactory.AuthContext ctx = testDataFactory.createAuthenticatedContext();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "Existing Repo Chat");
        Repository repository = testDataFactory.createRepository(
                ctx.team(), "existing-repo", "https://example.com/existing.git", "main", null);

        SandboxExecutionScenarioFactory.ExecutionScenario scenario =
                scenarioFactory.startOnConnector(ctx, chat, "Existing Repo Connector", "plan-existing", repository);

        assertThat(scenario.repository().getId()).isEqualTo(repository.getId());
        SandboxExecution saved =
                sandboxExecutionRepository.findById(scenario.executionId()).orElseThrow();
        assertThat(saved.getRepository().getId()).isEqualTo(repository.getId());
    }

    @Test
    void startNewRepoOnConnector_snapshotsNewRepoFields() {
        TestDataFactory.AuthContext ctx = testDataFactory.createAuthenticatedContext();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "New Repo Chat");
        RepoCredential credential =
                testDataFactory.createCredential(ctx.team(), "PAT", CredentialType.PAT, "pat-secret");

        SandboxExecutionScenarioFactory.ExecutionScenario scenario = scenarioFactory.startNewRepoOnConnector(
                ctx, chat, "New Repo Connector", "plan-new", "fresh-repo", credential.getId());

        assertThat(scenario.repository()).isNull();
        SandboxExecution saved =
                sandboxExecutionRepository.findById(scenario.executionId()).orElseThrow();
        assertThat(saved.getRepository()).isNull();
        assertThat(saved.getNewRepoName()).isEqualTo("fresh-repo");
        assertThat(saved.getNewRepoCredential().getId()).isEqualTo(credential.getId());
    }
}
