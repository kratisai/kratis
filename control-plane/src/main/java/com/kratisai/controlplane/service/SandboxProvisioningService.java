package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.api.wsdto.LaunchStatus;
import com.kratisai.controlplane.api.wsdto.RegisterGitAuthStatus;
import com.kratisai.controlplane.config.KratisProperties;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.*;
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

    /** Fallback context window when the model metadata does not declare one. */
    private static final long DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000;
    /** Upper bound for the proactive history window handed to harnesses. */
    private static final long MAX_HISTORY_WINDOW_TOKENS = 200_000;

    private static final long MIN_HISTORY_WINDOW_TOKENS = 32_000;

    private final SandboxOrchestratorService sandboxOrchestratorService;
    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
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

        EnvironmentConnectorResult.LaunchAcpAgent launch = environmentRpcClient.request(
                environmentId(execution),
                new EnvironmentRpcPayload.LaunchAcpAgent(
                        execution.getHarness().getAgentCommand(),
                        null,
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

    /** Deploys git credentials for the execution's sandbox; new-repo credentials are chosen at publish time. */
    public void registerGitAuth(SandboxExecution execution, RepoCredential credential)
            throws InterruptedException, TimeoutException {
        GitAuthMaterial auth = credentialResolver.resolve(credential);
        EnvironmentConnectorResult.RegisterGitAuth authResult = environmentRpcClient.request(
                environmentId(execution),
                new EnvironmentRpcPayload.RegisterGitAuth(
                        credential.getType().name(), auth.maybeSshKey().orElse(""), GIT_USER_NAME, gitUserEmail()));
        if (authResult == null || authResult.status() != RegisterGitAuthStatus.SUCCESS) {
            throw new IllegalStateException(
                    "Git auth registration failed: " + (authResult != null ? authResult.status() : "no result"));
        }
    }

    public void dispatchCheckout(SandboxExecution execution) throws Exception {
        Repository repository = execution.getRepository();
        String url = repository.getUrl();
        String branch = repository.getBranch();

        RepoCredential credential = repository.getCredential();

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
        RepoCredential credential = execution.getNewRepoCredential();
        if (credential != null) {
            registerGitAuth(execution, credential);
            logger.info(
                    "Registered git auth for new repository '{}' with execution environment {}",
                    execution.getNewRepoName(),
                    execution.getEnvironment().getId());
        }

        String initCommand =
                "if [ \"$(ls -A)\" ]; then echo \"Workspace is not empty\" >&2; exit 1; fi; git init -b main && git -c user.name=\""
                        + GIT_USER_NAME + "\" -c user.email=\"" + gitUserEmail()
                        + "\" commit --allow-empty -m \"Initial commit\"";
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

    private Map<String, String> buildVariableMap(SandboxExecution execution, String virtualKey) {
        Map<String, String> variables = new HashMap<>();
        variables.put("${VIRTUAL_KEY}", virtualKey != null ? virtualKey : "");
        variables.put("${LLM_BASE_URL}", litellmProperties.getSandboxBaseUrl());

        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(
                execution.getModelProvider(), execution.getModelName());
        variables.put("${LLM_MODEL}", litellmModelName);

        long contextWindow = resolveContextWindow(execution);
        variables.put("${LLM_CONTEXT_WINDOW}", Long.toString(contextWindow));
        // Proactive history-window budget for harnesses that manage context
        // explicitly: roughly 40% of the window, clamped to a sane range.
        long historyWindow = Math.clamp(contextWindow * 2 / 5, MIN_HISTORY_WINDOW_TOKENS, MAX_HISTORY_WINDOW_TOKENS);
        variables.put("${LLM_HISTORY_MAX_TOKENS}", Long.toString(historyWindow));

        return variables;
    }

    /** Context window in tokens for the execution's model, falling back to a default when metadata omits it. */
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

    /** Git author email for platform commits, derived from the control-plane hostname. */
    private String gitUserEmail() {
        return GIT_USER_NAME.toLowerCase() + "@" + kratisProperties.resolveHostname();
    }
}
