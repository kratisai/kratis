package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SlowTest;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.api.restdto.SandboxExecutionDto;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * Integration test verifying that creating a sandbox execution with a canvasId correctly resolves
 * the canvas content and prepends it to the task prompt.
 */
@SpringIntegrationTest
@SlowTest
class CanvasToExecutionIntegrationTest {

    @Autowired
    private SandboxExecutionService sandboxExecutionService;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    private User user;
    private ChatEntity chat;
    private ExecutionEnvironment environment;
    private ModelProvider modelProvider;
    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.AuthContext ctx = testDataFactory.createAuthenticatedContext();
        user = ctx.user();
        chat = testDataFactory.createChat(ctx.team(), user, "Canvas Execution Test");
        environment = ctx.defaultSandbox();
        modelProvider = ctx.provider();
        repository = testDataFactory.createRepository(
                ctx.team(), "backend", "https://example.com/backend.git", "main", null);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private void createSpecCanvas(String documentId, String content) {
        canvasService.createCanvas(chat.getId(), documentId, "My Plan", content, CanvasType.SPEC, repository, null);
    }

    @Test
    void shouldPrependCanvasContentToTaskPrompt() {
        // Create a SPEC canvas
        String planContent = "# Plan\n1. Step one\n2. Step two";
        createSpecCanvas("plan-doc", planContent);

        // Create execution request with canvasId
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-doc", modelProvider.getId(), "gpt-4o");

        SandboxExecutionDto execution = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        // Assert task prompt includes canvas content wrapped in plan_context tags
        assertThat(execution.taskPrompt()).contains("<plan_context>");
        assertThat(execution.taskPrompt()).contains("# Plan");
        assertThat(execution.taskPrompt()).contains("Step one");
        assertThat(execution.taskPrompt()).contains("</plan_context>");
        assertThat(execution.taskPrompt()).contains("Execute the plan");

        // Assert the Docker sandbox facts that the agent cannot infer
        assertThat(execution.taskPrompt()).contains("### Docker").contains("$TESTCONTAINERS_HOST_OVERRIDE");
    }

    @Test
    void shouldUseRequestedAgentHarnessAndExecutionTarget() {
        String planContent = "# Plan\nDo something";
        createSpecCanvas("plan-doc-2", planContent);

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-doc-2", modelProvider.getId(), "gpt-4o");

        SandboxExecutionDto execution = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        assertThat(execution.harness()).isEqualTo(AgentHarness.OPENCODE);
        assertThat(execution.chatId()).isEqualTo(chat.getId());
    }

    @Test
    void shouldThrowWhenCanvasIdIsNull() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, null, modelProvider.getId(), "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("canvasId is required");
    }

    @Test
    void shouldThrowWhenCanvasIdIsBlank() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "  ", modelProvider.getId(), "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("canvasId is required");
    }

    @Test
    void shouldThrowWhenCanvasDoesNotExist() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                null,
                AgentHarness.OPENCODE,
                "nonexistent-canvas",
                modelProvider.getId(),
                "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Canvas document not found");
    }

    @Test
    void createExecution_withModelProviderAndModelName_storesThem() {
        String planContent = "# Plan\nUse gpt-4o";
        createSpecCanvas("plan-mp", planContent);

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-mp", modelProvider.getId(), "gpt-4o");

        SandboxExecutionDto dto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        // Verify via repository that model provider and model name are stored
        SandboxExecution saved = sandboxExecutionRepository.findById(dto.id()).orElseThrow();
        assertThat(saved.getModelProvider()).isNotNull();
        assertThat(saved.getModelProvider().getId()).isEqualTo(modelProvider.getId());
        assertThat(saved.getModelName()).isEqualTo("gpt-4o");
    }

    @Test
    void createExecution_withNullModelProvider_throwsBadRequest() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-nm", null, null);

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("modelProviderId is required");
    }

    @Test
    void createExecution_withNullModelName_throwsBadRequest() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-mn", modelProvider.getId(), null);

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("modelName is required");
    }

    @Test
    void createExecution_withInvalidModelProviderId_throwsNotFound() {
        String planContent = "# Plan\nBad provider";
        createSpecCanvas("plan-bp", planContent);

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-bp", UUID.randomUUID(), "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Model provider not found");
    }

    @Test
    void createExecution_withRepositoryCanvas_resolvesRepositoryFromCanvas() {
        createSpecCanvas("plan-repo", "# Plan\nRepo work");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), null, AgentHarness.OPENCODE, "plan-repo", modelProvider.getId(), "gpt-4o");

        SandboxExecutionDto dto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        SandboxExecution saved = sandboxExecutionRepository.findById(dto.id()).orElseThrow();
        assertThat(saved.getRepository().getId()).isEqualTo(repository.getId());
        assertThat(saved.getNewRepoName()).isNull();
        assertThat(saved.getNewRepoCredential()).isNull();
    }

    @Test
    void createExecution_withDocumentCanvas_rejected() {
        canvasService.createCanvas(
                chat.getId(), "plan-doc-only", "Plain Doc", "# Notes", CanvasType.DOCUMENT, null, null);

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                null,
                AgentHarness.OPENCODE,
                "plan-doc-only",
                modelProvider.getId(),
                "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be launched");
    }

    @Test
    void createExecution_withNewRepoCanvasWithoutCredentialId_rejected() {
        canvasService.createCanvas(
                chat.getId(), "plan-new-nocred", "New Repo Plan", "# Plan", CanvasType.SPEC, null, "fresh-repo");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                null,
                AgentHarness.OPENCODE,
                "plan-new-nocred",
                modelProvider.getId(),
                "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("credentialId is required");
    }

    @Test
    void createExecution_withNewRepoCanvasAndCredential_snapshotsFields() {
        RepoCredential credential =
                testDataFactory.createCredential(chat.getTeam(), "PAT", CredentialType.PAT, "pat-secret");
        canvasService.createCanvas(
                chat.getId(), "plan-new", "New Repo Plan", "# Plan", CanvasType.SPEC, null, "fresh-repo");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                credential.getId(),
                AgentHarness.OPENCODE,
                "plan-new",
                modelProvider.getId(),
                "gpt-4o");

        SandboxExecutionDto dto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        SandboxExecution saved = sandboxExecutionRepository.findById(dto.id()).orElseThrow();
        assertThat(saved.getRepository()).isNull();
        assertThat(saved.getNewRepoName()).isEqualTo("fresh-repo");
        assertThat(saved.getNewRepoCredential().getId()).isEqualTo(credential.getId());
    }

    @Test
    void createExecution_withNewRepoCanvasAndUnknownCredentialId_throwsNotFound() {
        canvasService.createCanvas(
                chat.getId(), "plan-new-badcred", "New Repo Plan", "# Plan", CanvasType.SPEC, null, "fresh-repo");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                UUID.randomUUID(),
                AgentHarness.OPENCODE,
                "plan-new-badcred",
                modelProvider.getId(),
                "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Credential not found");
    }
}
