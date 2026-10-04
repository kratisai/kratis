package com.kratisai.controlplane.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.api.wsdto.GitRegistrationStatus;
import com.kratisai.controlplane.api.wsdto.LaunchStatus;
import com.kratisai.controlplane.config.KratisProperties;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CanvasRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SandboxProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxProvisioningService.class);

    private static final long DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000;

    private final SandboxOrchestratorService sandboxOrchestratorService;
    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final CanvasRepository canvasRepository;
    private final GitCredentialResolver credentialResolver;
    private final VirtualKeyService virtualKeyService;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final LiteLLMProperties litellmProperties;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final EnvironmentRpcClient environmentRpcClient;
    private final KratisProperties kratisProperties;

    private static final String GIT_USER_NAME = "Kratis";

    public SandboxProvisioningService(
            PlatformTransactionManager transactionManager,
            SandboxOrchestratorService sandboxOrchestratorService,
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            CanvasRepository canvasRepository,
            GitCredentialResolver credentialResolver,
            VirtualKeyService virtualKeyService,
            LiteLLMProvisioningService litellmProvisioningService,
            LiteLLMProperties litellmProperties,
            EnvironmentRpcClient environmentRpcClient,
            KratisProperties kratisProperties) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.sandboxOrchestratorService = sandboxOrchestratorService;
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.canvasRepository = canvasRepository;
        this.credentialResolver = credentialResolver;
        this.virtualKeyService = virtualKeyService;
        this.litellmProvisioningService = litellmProvisioningService;
        this.litellmProperties = litellmProperties;
        this.environmentRpcClient = environmentRpcClient;
        this.kratisProperties = kratisProperties;
    }

    public void provisionEnvironment(ExecutionEnvironment environment, String token) {
        SandboxProvider sandboxProvider =
                sandboxOrchestratorService.getProvider(environment.getProvider().getType());
        String containerId = sandboxProvider.spawnSandbox(environment, token);

        transactionTemplate.executeWithoutResult(status -> {
            ExecutionEnvironment env =
                    executionEnvironmentRepository.findById(environment.getId()).orElseThrow();
            env.setContainerId(containerId);
            executionEnvironmentRepository.save(env);
        });

        sandboxProvider.initializeWorkspace(containerId);
    }

    public void launchAgent(SandboxExecution execution) throws Exception {
        if (execution.getNewRepoName() != null) {
            prepareNewRepository(execution);
        }
        verifyRepositoryCheckout(execution);
        ensureModelRegistered(execution);
        // writeActiveTaskFile may run outside the load transaction, so reload the linked canvas
        // here; without it the lazy association is detached when the file is written.
        if (execution.getCanvas() != null) {
            execution.setCanvas(reloadCanvas(execution));
        }
        writeActiveTaskFile(execution);

        String virtualKey = requiresNewTransactionTemplate.execute(status -> {
            SandboxExecution persisted =
                    sandboxExecutionRepository.findById(execution.getId()).orElse(execution);
            String existingKey = persisted.getUsage().getVirtualKey();
            if (existingKey != null && !existingKey.isBlank()) {
                logger.info("Virtual key already exists for execution {}, reusing it", execution.getId());
                execution.getUsage().setVirtualKey(existingKey);
                return existingKey;
            }

            String keyAlias = litellmProvisioningService.buildVirtualKeyAlias(
                    LiteLLMProvisioningService.VirtualKeyScope.SANDBOX, execution.getId());
            String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(
                    execution.getModelProvider(), execution.getModelName());
            String token = virtualKeyService.generateKey(keyAlias, List.of(litellmModelName));
            execution.getUsage().setVirtualKey(token);
            sandboxExecutionRepository.saveAndFlush(execution);
            return token;
        });

        writeHarnessResources(execution);

        Map<String, String> variables = buildVariableMap(execution, virtualKey);
        List<String> commands = resolveSetupCommands(execution.getHarness().getSetupCommands(), variables);

        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            if (command == null || command.isBlank()) {
                continue;
            }
            logger.info(
                    "Dispatching setup command {}/{} for execution {} via env.exec",
                    i + 1,
                    commands.size(),
                    execution.getId());
            runSetupCommand(execution, command);
        }

        EnvironmentConnectorResult.LaunchAcpAgent launch = environmentRpcClient.request(
                environmentId(execution),
                new EnvironmentRpcPayload.LaunchAcpAgent(
                        execution.getHarness().getAgentCommand(),
                        null,
                        execution.getHarness().getAgentLogFiles(),
                        execution.getId().toString()));
        if (launch == null || launch.status() == LaunchStatus.FAILED) {
            String error =
                    launch != null && launch.error() != null ? launch.error() : "ACP agent launch returned no result";
            throw new IllegalStateException("ACP agent launch failed: " + error);
        }
        logger.info(
                "Launched ACP agent using harness '{}' (session {}) for execution {}",
                execution.getHarness().getName(),
                launch.sessionId(),
                execution.getId());
    }

    public void registerGitIdentity(SandboxExecution execution) throws InterruptedException, TimeoutException {
        EnvironmentConnectorResult.RegisterGitIdentity identityResult = environmentRpcClient.request(
                environmentId(execution), new EnvironmentRpcPayload.RegisterGitIdentity(GIT_USER_NAME, gitUserEmail()));
        if (identityResult == null || identityResult.status() != GitRegistrationStatus.SUCCESS) {
            throw new IllegalStateException("Git identity registration failed: "
                    + (identityResult != null ? identityResult.status() : "no result"));
        }
    }

    public void registerGitAuth(SandboxExecution execution, RepoCredential credential)
            throws InterruptedException, TimeoutException {
        GitAuthMaterial auth = credentialResolver.resolve(credential);
        EnvironmentConnectorResult.RegisterGitAuth authResult = environmentRpcClient.request(
                environmentId(execution),
                new EnvironmentRpcPayload.RegisterGitAuth(
                        credential.getType().name(), auth.maybeSshKey().orElse("")));
        if (authResult == null || authResult.status() != GitRegistrationStatus.SUCCESS) {
            throw new IllegalStateException(
                    "Git auth registration failed: " + (authResult != null ? authResult.status() : "no result"));
        }
    }

    public void dispatchCheckout(SandboxExecution execution) throws Exception {
        Repository repository = execution.getRepository();
        String url = repository.getUrl();
        String branch = repository.getBranch();

        RepoCredential credential = repository.getCredential();

        registerGitIdentity(execution);
        if (credential != null) {
            registerGitAuth(execution, credential);
            logger.info(
                    "Registered git auth with execution environment {}",
                    execution.getEnvironment().getId());
        }

        Map<String, String> gitEnv = Map.of("GIT_TERMINAL_PROMPT", "0", "SSH_ASKPASS", "");
        environmentRpcClient.send(
                environmentId(execution),
                new EnvironmentRpcPayload.Checkout(
                        url,
                        branch != null ? branch : "main",
                        gitEnv,
                        execution.getId().toString()));
        logger.info(
                "Dispatched checkout of {} to execution environment {} for execution {}",
                url,
                execution.getEnvironment().getId(),
                execution.getId());
    }

    private void prepareNewRepository(SandboxExecution execution) throws Exception {
        registerGitIdentity(execution);

        RepoCredential credential = execution.getNewRepoCredential();
        if (credential != null) {
            registerGitAuth(execution, credential);
            logger.info(
                    "Registered git auth for new repository '{}' with execution environment {}",
                    execution.getNewRepoName(),
                    execution.getEnvironment().getId());
        }

        String initCommand =
                "if [ -n \"$(ls -A | grep -v '^\\.kratis$')\" ]; then echo \"Workspace is not empty\" >&2; exit 1; fi; git init -b main && git commit --allow-empty -m \"Initial commit\"";
        EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                environmentId(execution),
                new EnvironmentRpcPayload.Exec(
                        initCommand, false, execution.getId().toString()));
        if (result == null || result.status() == ExecStatus.FAILED || result.exitCode() != 0) {
            String error = result != null && result.error() != null
                    ? result.error()
                    : "exit code " + (result != null ? result.exitCode() : -1);
            throw new IllegalStateException("New repository initialization failed: " + error);
        }
        logger.info(
                "Initialized new git repository '{}' in execution environment {}",
                execution.getNewRepoName(),
                execution.getEnvironment().getId());
    }

    private void writeActiveTaskFile(SandboxExecution execution) throws InterruptedException, TimeoutException {
        runSetupCommand(
                execution,
                ActiveTaskPrompt.buildWriteCommands(
                        ActiveTaskPrompt.buildActiveTaskDocument(execution.getId(), canvasContent(execution))));
    }

    // Harness definitions are pure data, so files they need (e.g. the gemini ACP patch) are declared
    // as resources and materialised here before the setup commands that reference them run.
    private void writeHarnessResources(SandboxExecution execution) throws InterruptedException, TimeoutException {
        for (HarnessResource resource : execution.getHarness().getResources()) {
            runSetupCommand(
                    execution,
                    SandboxFiles.writeCommands(resource.target(), SandboxFiles.readClasspath(resource.source())));
        }
    }

    private String canvasContent(SandboxExecution execution) {
        CanvasEntity canvas = execution.getCanvas();
        if (canvas == null || isBlank(canvas.getContent())) {
            throw new IllegalStateException("Canvas must be provided for an execution");
        }
        return canvas.getContent();
    }

    private CanvasEntity reloadCanvas(SandboxExecution execution) {
        Long canvasId = execution.getCanvas().getId();
        return canvasRepository.findById(canvasId).orElse(null);
    }

    private void verifyRepositoryCheckout(SandboxExecution execution) throws InterruptedException, TimeoutException {
        if (execution.getRepository() == null && execution.getNewRepoName() == null) {
            return;
        }
        EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                environmentId(execution),
                new EnvironmentRpcPayload.Exec(
                        "git rev-parse --verify HEAD", false, execution.getId().toString()));
        if (result == null || result.status() == ExecStatus.FAILED || result.exitCode() != 0) {
            String error = result != null && result.error() != null
                    ? result.error()
                    : "exit code " + (result != null ? result.exitCode() : -1);
            throw new IllegalStateException("Repository checkout verification failed: " + error);
        }
    }

    private void ensureModelRegistered(SandboxExecution execution) {
        if (!execution.getModelProvider().getModelNames().contains(execution.getModelName())) {
            throw new IllegalStateException("Model '" + execution.getModelName() + "' not found in provider '"
                    + execution.getModelProvider().getDisplayName() + "'");
        }
        ModelProvider provider = execution.getModelProvider();
        String modelName = execution.getModelName();
        if (!litellmProvisioningService.verifyModelRegistered(provider, modelName)) {
            throw new IllegalStateException(
                    "Model '" + modelName + "' is not registered in LiteLLM after provisioning");
        }
    }

    private static UUID environmentId(SandboxExecution execution) {
        return execution.getEnvironment().getId();
    }

    private void runSetupCommand(SandboxExecution execution, String command)
            throws InterruptedException, TimeoutException {
        runSetupCommand(execution, List.of(command));
    }

    private void runSetupCommand(SandboxExecution execution, List<String> commands)
            throws InterruptedException, TimeoutException {
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                    environmentId(execution),
                    new EnvironmentRpcPayload.Exec(
                            command, true, execution.getId().toString()));
            if (result == null || result.status() == ExecStatus.FAILED || result.exitCode() != 0) {
                String error = result != null && result.error() != null
                        ? result.error()
                        : "Setup command failed with exit code " + (result != null ? result.exitCode() : -1);
                throw new IllegalStateException("Setup command failed: " + error);
            }
        }
    }

    private Map<String, String> buildVariableMap(SandboxExecution execution, String virtualKey) {
        Map<String, String> variables = new HashMap<>();
        variables.put("${VIRTUAL_KEY}", virtualKey != null ? virtualKey : "");
        variables.put("${LLM_BASE_URL}", litellmProperties.getSandboxBaseUrl());

        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(
                execution.getModelProvider(), execution.getModelName());
        variables.put("${LLM_MODEL}", litellmModelName);

        long contextWindow = resolveContextWindow(execution);
        variables.put("${LLM_CONTEXT_WINDOW}", Long.toString(contextWindow));

        return variables;
    }

    private long resolveContextWindow(SandboxExecution execution) {
        return execution.getModelProvider().getModels().stream()
                .filter(model -> model.getModelName().equals(execution.getModelName()))
                .map(ProviderModel::getContextWindowTokens)
                .filter(Objects::nonNull)
                .filter(tokens -> tokens > 0)
                .findFirst()
                .orElse(DEFAULT_CONTEXT_WINDOW_TOKENS);
    }

    private List<String> resolveSetupCommands(List<String> commands, Map<String, String> variables) {
        if (commands == null) {
            return List.of();
        }

        return commands.stream()
                .map(cmd -> {
                    String resolved = cmd;
                    for (Map.Entry<String, String> entry : variables.entrySet()) {
                        resolved = resolved.replace(entry.getKey(), entry.getValue());
                    }
                    return resolved;
                })
                .toList();
    }

    private String gitUserEmail() {
        return GIT_USER_NAME.toLowerCase() + "@" + kratisProperties.resolveHostname();
    }
}
