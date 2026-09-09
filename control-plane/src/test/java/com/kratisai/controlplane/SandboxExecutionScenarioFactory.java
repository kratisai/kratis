package com.kratisai.controlplane;

import com.kratisai.controlplane.api.restdto.CreateExecutionEnvironmentRequest;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ExecutionEnvironmentService;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
public class SandboxExecutionScenarioFactory {

    public record ExecutionScenario(
            UUID envId, String connectorToken, UUID executionId, Repository repository, String canvasDocumentId) {
        public ExecutionScenario {
            Objects.requireNonNull(envId, "envId");
            Objects.requireNonNull(connectorToken, "connectorToken");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(canvasDocumentId, "canvasDocumentId");
        }
    }

    private final TestDataFactory testDataFactory;
    private final ExecutionEnvironmentService executionEnvironmentService;
    private final CanvasService canvasService;
    private final SandboxExecutionService sandboxExecutionService;

    public SandboxExecutionScenarioFactory(
            TestDataFactory testDataFactory,
            ExecutionEnvironmentService executionEnvironmentService,
            CanvasService canvasService,
            SandboxExecutionService sandboxExecutionService) {
        this.testDataFactory = testDataFactory;
        this.executionEnvironmentService = executionEnvironmentService;
        this.canvasService = canvasService;
        this.sandboxExecutionService = sandboxExecutionService;
    }

    public ExecutionScenario startOnConnector(
            TestDataFactory.AuthContext ctx,
            ChatEntity chat,
            String connectorName,
            String canvasDocumentId,
            String repoName) {
        Repository repository = testDataFactory.createRepository(
                ctx.team(), repoName, "https://example.com/" + repoName + ".git", "main", null);
        return startOnConnector(ctx, chat, connectorName, canvasDocumentId, repository);
    }

    public ExecutionScenario startOnConnector(
            TestDataFactory.AuthContext ctx,
            ChatEntity chat,
            String connectorName,
            String canvasDocumentId,
            Repository repository) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(connectorName, "connectorName");
        Objects.requireNonNull(canvasDocumentId, "canvasDocumentId");
        Objects.requireNonNull(repository, "repository");
        var response = executionEnvironmentService.createConnector(
                ctx.user().getId(), ctx.team().getId(), new CreateExecutionEnvironmentRequest(connectorName));
        UUID envId = response.environment().id();
        String connectorToken = response.environment().authToken();
        canvasService.createCanvas(
                chat.getId(), canvasDocumentId, "Test Plan", "# Test Plan", CanvasType.SPEC, repository, null);
        UUID executionId = sandboxExecutionService
                .createExecution(
                        ctx.user().getId(),
                        chat.getId(),
                        new CreateSandboxExecutionRequest(
                                null,
                                envId,
                                null,
                                AgentHarness.OPENCODE,
                                canvasDocumentId,
                                ctx.provider().getId(),
                                TestDataFactory.DEFAULT_CHAT_MODEL))
                .id();
        return new ExecutionScenario(envId, connectorToken, executionId, repository, canvasDocumentId);
    }

    public ExecutionScenario startNewRepoOnConnector(
            TestDataFactory.AuthContext ctx,
            ChatEntity chat,
            String connectorName,
            String canvasDocumentId,
            String newRepoName,
            UUID credentialId) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(connectorName, "connectorName");
        Objects.requireNonNull(canvasDocumentId, "canvasDocumentId");
        Objects.requireNonNull(newRepoName, "newRepoName");
        Objects.requireNonNull(credentialId, "credentialId");
        var response = executionEnvironmentService.createConnector(
                ctx.user().getId(), ctx.team().getId(), new CreateExecutionEnvironmentRequest(connectorName));
        UUID envId = response.environment().id();
        String connectorToken = response.environment().authToken();
        canvasService.createCanvas(
                chat.getId(), canvasDocumentId, "Test Plan", "# Test Plan", CanvasType.SPEC, null, newRepoName);
        UUID executionId = sandboxExecutionService
                .createExecution(
                        ctx.user().getId(),
                        chat.getId(),
                        new CreateSandboxExecutionRequest(
                                null,
                                envId,
                                credentialId,
                                AgentHarness.OPENCODE,
                                canvasDocumentId,
                                ctx.provider().getId(),
                                TestDataFactory.DEFAULT_CHAT_MODEL))
                .id();
        return new ExecutionScenario(envId, connectorToken, executionId, null, canvasDocumentId);
    }
}
