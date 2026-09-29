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
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
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
 * the canvas content and embeds it in the task prompt after the fixed instruction block.
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
    void shouldEmbedCanvasContentInTaskPrompt() {
        // Create a SPEC canvas
        String planContent = "# Plan\n1. Step one\n2. Step two";
        createSpecCanvas("plan-doc", planContent);

        // Create execution request with canvasId
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                AgentHarness.valueOf("OPENCODE"),
                "plan-doc",
                modelProvider.getId(),
                "gpt-4o");

        SandboxExecutionDto execution = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        // The canvas is appended after the delimiter that closes the fixed instructions.
        assertThat(execution.taskPrompt())
                .contains("### User Task follows - use the above structure to fully deliver this task");
        assertThat(execution.taskPrompt()).contains("=====================================");
        assertThat(execution.taskPrompt()).contains("# Plan");
        assertThat(execution.taskPrompt()).contains("Step one");
        assertThat(execution.taskPrompt())
                .contains("Execute the user's task systematically: prepare, execute and verify.");
        assertThat(execution.taskPrompt()).contains(".kratis/ACTIVE_TASK.md");
        // The simplified prompt no longer wraps the canvas in plan_context markers.
        assertThat(execution.taskPrompt()).doesNotContain("<plan_context>");

        // The execution links the source canvas so provisioning builds ACTIVE_TASK.md from it.
        SandboxExecution saved =
                sandboxExecutionRepository.findById(execution.id()).orElseThrow();
        assertThat(saved.getCanvas()).isNotNull();
        CanvasEntity linked = canvasService.getCanvas(saved.getChat().getId(), "plan-doc");
        assertThat(linked).isNotNull();
        assertThat(linked.getContent()).isEqualTo(planContent);
        assertThat(saved.getCanvas().getId()).isEqualTo(linked.getId());
    }

    @Test
    void shouldUseRequestedAgentHarnessAndExecutionTarget() {
        String planContent = "# Plan\nDo something";
        createSpecCanvas("plan-doc-2", planContent);

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                AgentHarness.valueOf("OPENCODE"),
                "plan-doc-2",
                modelProvider.getId(),
                "gpt-4o");

        SandboxExecutionDto execution = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        assertThat(execution.harness()).isEqualTo(AgentHarness.valueOf("OPENCODE"));
        assertThat(execution.chatId()).isEqualTo(chat.getId());
    }

    @Test
    void shouldThrowWhenCanvasIdIsNull() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), AgentHarness.valueOf("OPENCODE"), null, modelProvider.getId(), "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("canvasId is required");
    }

    @Test
    void shouldThrowWhenCanvasIdIsBlank() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), AgentHarness.valueOf("OPENCODE"), "  ", modelProvider.getId(), "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("canvasId is required");
    }

    @Test
    void shouldThrowWhenCanvasDoesNotExist() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                AgentHarness.valueOf("OPENCODE"),
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
                null,
                environment.getId(),
                AgentHarness.valueOf("OPENCODE"),
                "plan-mp",
                modelProvider.getId(),
                "gpt-4o");

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
                null, environment.getId(), AgentHarness.valueOf("OPENCODE"), "plan-nm", null, null);

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("modelProviderId is required");
    }

    @Test
    void createExecution_withNullModelName_throwsBadRequest() {
        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), AgentHarness.valueOf("OPENCODE"), "plan-mn", modelProvider.getId(), null);

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("modelName is required");
    }

    @Test
    void createExecution_withInvalidModelProviderId_throwsNotFound() {
        String planContent = "# Plan\nBad provider";
        createSpecCanvas("plan-bp", planContent);

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null, environment.getId(), AgentHarness.valueOf("OPENCODE"), "plan-bp", UUID.randomUUID(), "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Model provider not found");
    }

    @Test
    void createExecution_withRepositoryCanvas_resolvesRepositoryFromCanvas() {
        createSpecCanvas("plan-repo", "# Plan\nRepo work");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                AgentHarness.valueOf("OPENCODE"),
                "plan-repo",
                modelProvider.getId(),
                "gpt-4o");

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
                AgentHarness.valueOf("OPENCODE"),
                "plan-doc-only",
                modelProvider.getId(),
                "gpt-4o");

        assertThatThrownBy(() -> sandboxExecutionService.createExecution(user.getId(), chat.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be launched");
    }

    @Test
    void createExecution_withNewRepoCanvas_snapshotsNameForPublishTimeSelection() {
        canvasService.createCanvas(
                chat.getId(), "plan-new-nocred", "New Repo Plan", "# Plan", CanvasType.SPEC, null, "fresh-repo");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                environment.getId(),
                AgentHarness.valueOf("OPENCODE"),
                "plan-new-nocred",
                modelProvider.getId(),
                "gpt-4o");

        SandboxExecutionDto dto = sandboxExecutionService.createExecution(user.getId(), chat.getId(), request);

        SandboxExecution saved = sandboxExecutionRepository.findById(dto.id()).orElseThrow();
        assertThat(saved.getNewRepoName()).isEqualTo("fresh-repo");
        assertThat(saved.getNewRepoCredential()).isNull();
    }
}
