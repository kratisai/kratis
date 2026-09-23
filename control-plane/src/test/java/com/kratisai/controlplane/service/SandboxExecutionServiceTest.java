package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.ResourcelessTransactionManager;
import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.api.restdto.SandboxExecutionDto;
import com.kratisai.controlplane.api.restdto.SteerExecutionRequest;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.LaunchStatus;
import com.kratisai.controlplane.api.wsdto.PromptStatus;
import com.kratisai.controlplane.api.wsdto.RegisterGitAuthStatus;
import com.kratisai.controlplane.api.wsdto.StopReason;
import com.kratisai.controlplane.config.KratisProperties;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.model.event.ExecutionStatusChangedEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class SandboxExecutionServiceTest {

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Mock
    private EnvironmentProviderRepository environmentProviderRepository;

    @Mock
    private SandboxOrchestratorService sandboxOrchestratorService;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private GitCredentialResolver credentialResolver;

    @Mock
    private VirtualKeyService virtualKeyService;

    @Mock
    private CanvasService canvasService;

    @Mock
    private ModelProviderRepository modelProviderRepository;

    @Mock
    private LiteLLMProvisioningService litellmProvisioningService;

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    @Mock
    private WebSocketSession webSocketSession;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    @Mock
    private SandboxProvider sandboxProvider;

    @Mock
    private SandboxExecutionActivityRepository activityRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SandboxProvisioningService sandboxProvisioningService;
    private SandboxExecutionService sandboxExecutionService;
    private ExecutionActivityPersistenceService activityPersistenceService;
    private LiteLLMProperties litellmProperties;

    private static final UUID TEAM_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CHAT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID ENVIRONMENT_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID EXECUTION_ID = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
    private static final UUID USER_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @BeforeEach
    void setUp() {
        litellmProperties = new LiteLLMProperties();
        litellmProperties.setBaseUrl("http://localhost:4000");
        // No pause before the final usage fetch in unit tests; the delay is a production concern.
        litellmProperties.setUsageFinalizeDelay(Duration.ZERO);
        sandboxProvisioningService = new SandboxProvisioningService(
                transactionManager,
                sandboxOrchestratorService,
                executionEnvironmentRepository,
                sandboxExecutionRepository,
                credentialResolver,
                virtualKeyService,
                litellmProvisioningService,
                litellmProperties,
                environmentRpcClient,
                createKratisProperties());

        activityPersistenceService = new ExecutionActivityPersistenceService(
                activityRepository, sandboxExecutionRepository, objectMapper, eventPublisher);

        sandboxExecutionService = new SandboxExecutionService(
                transactionManager,
                sandboxExecutionRepository,
                chatRepository,
                executionEnvironmentRepository,
                environmentProviderRepository,
                teamMemberRepository,
                eventPublisher,
                sessionRegistry,
                canvasService,
                modelProviderRepository,
                sandboxProvisioningService,
                pendingHitlRegistry,
                virtualKeyService,
                environmentRpcClient,
                litellmProperties,
                Runnable::run,
                activityPersistenceService);
    }

    private void stubSuccessfulExec() throws Exception {
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.Exec.class)))
                .thenReturn(new EnvironmentConnectorResult.Exec(ExecStatus.COMPLETED, 0));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.LaunchAcpAgent.class)))
                .thenReturn(new EnvironmentConnectorResult.LaunchAcpAgent(
                        LaunchStatus.LAUNCHED, "session-1", "claude", "1.0", null));
    }

    private SandboxExecution createTestExecution() {
        return createTestExecution(AgentHarness.OPENCODE, createTestModelProvider(), "gpt-4o");
    }

    private KratisProperties createKratisProperties() {
        KratisProperties properties = new KratisProperties();
        properties.setHostname("control-plane.test");
        return properties;
    }

    private SandboxExecution createTestExecution(ModelProvider modelProvider) {
        return createTestExecution(AgentHarness.OPENCODE, modelProvider, "gpt-99");
    }

    private SandboxExecution createTestExecution(AgentHarness harness, ModelProvider modelProvider, String modelName) {
        Team team = new Team();
        team.setId(TEAM_ID);

        ChatEntity chat = new ChatEntity();
        chat.setId(CHAT_ID);
        chat.setTeam(team);

        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(ENVIRONMENT_ID);

        SandboxExecution execution = new SandboxExecution();
        execution.setId(EXECUTION_ID);
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setHarness(harness);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setModelProvider(modelProvider);
        execution.setModelName(modelName);
        return execution;
    }

    private ModelProvider createTestModelProvider() {
        Team team = new Team();
        team.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));

        ModelProvider provider = new ModelProvider("Test Provider", ProviderType.OPENAI, "sk-key", null);
        provider.setId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        provider.setTeam(team);
        provider.setModels(
                List.of(new ProviderModel("gpt-4o", ModelKind.CHAT), new ProviderModel("gpt-4o-mini", ModelKind.CHAT)));
        return provider;
    }

    @Test
    void launchAgent_withValidModel_substitutesVariablesInSetupCommands() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENHANDS, provider, "gpt-4o");

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-virtual-key");
        stubSuccessfulExec();

        sandboxProvisioningService.launchAgent(execution);

        verify(litellmProvisioningService, atLeast(1)).buildLiteLLMModelName(provider, "gpt-4o");

        ArgumentCaptor<EnvironmentRpcPayload.Exec> execCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, atLeastOnce()).request(eq(ENVIRONMENT_ID), execCaptor.capture());

        String allExecCommands = execCaptor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allExecCommands).contains("sk-virtual-key");
        assertThat(allExecCommands).contains("http://localhost:4000");
        assertThat(allExecCommands).contains("openai-test-provider-gpt-4o-11111111");
        assertThat(allExecCommands).doesNotContain("${VIRTUAL_KEY}");
        assertThat(allExecCommands).doesNotContain("${LLM_BASE_URL}");
        assertThat(allExecCommands).doesNotContain("${LLM_MODEL}");

        ArgumentCaptor<EnvironmentRpcPayload.LaunchAcpAgent> launchCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.LaunchAcpAgent.class);
        verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), launchCaptor.capture());
        assertThat(launchCaptor.getValue().agentCommand()).isEqualTo(AgentHarness.OPENHANDS.getAgentCommand());
    }

    @Test
    void launchAgent_withInvalidModel_throwsException() {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(provider);

        assertThatThrownBy(() -> sandboxProvisioningService.launchAgent(execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-99");

        verify(litellmProvisioningService, never()).verifyModelRegistered(any(), any());
        verify(litellmProvisioningService, never()).provisionModel(any());
    }

    @Test
    void launchAgent_withModelNotRegisteredInLiteLLM_noLongerVerifiesAtLaunch() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENCODE, provider, "gpt-4o");

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-virtual-key");
        stubSuccessfulExec();

        sandboxProvisioningService.launchAgent(execution);

        assertThat(execution.getStatus()).isNotEqualTo(SandboxExecutionStatus.FAILED);
        verify(litellmProvisioningService).verifyModelRegistered(any(), any());
    }

    @Test
    void launchAgent_withModelPreviouslyNotRegistered_autoProvisionsAtProviderLifecycle() {
        ModelProvider provider = createTestModelProvider();
        assertThat(provider.getModelNames()).contains("gpt-4o");
    }

    @Test
    void launchAgent_onFailure_publishesCompleteEventAndStatusChangedEvent() {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(provider);

        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.dispatchExecution(execution, webSocketSession);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

            List<Object> events = eventCaptor.getAllValues();
            boolean hasCompleteEvent = events.stream().anyMatch(SandboxExecutionCompleteEvent.class::isInstance);
            boolean hasStatusChangedEvent = events.stream().anyMatch(ExecutionStatusChangedEvent.class::isInstance);

            assertThat(hasCompleteEvent).isTrue();
            assertThat(hasStatusChangedEvent).isTrue();

            SandboxExecutionCompleteEvent completeEvent = events.stream()
                    .filter(SandboxExecutionCompleteEvent.class::isInstance)
                    .map(SandboxExecutionCompleteEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(completeEvent.status()).isEqualTo(SandboxExecutionStatus.FAILED);
            assertThat(completeEvent.executionId()).isEqualTo(execution.getId());
        });
    }

    @Test
    void launchAgent_withNullVirtualKey_substitutesEmptyStringForVirtualKey() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENHANDS, provider, "gpt-4o");

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn(null);
        stubSuccessfulExec();

        sandboxProvisioningService.launchAgent(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Exec> execCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, atLeastOnce()).request(eq(ENVIRONMENT_ID), execCaptor.capture());
        String allExecCommands = execCaptor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allExecCommands).doesNotContain("${VIRTUAL_KEY}");
        assertThat(allExecCommands).doesNotContain("${LLM_BASE_URL}");
        assertThat(allExecCommands).contains("http://localhost:4000");
        assertThat(allExecCommands).doesNotContain("${LLM_MODEL}");
        assertThat(allExecCommands).contains("openai-test-provider-gpt-4o-11111111");
    }

    @Test
    void launchAgent_withRepository_verifiesCheckoutBeforeLaunch() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENCODE, provider, "gpt-4o");
        Repository repository = new Repository();
        repository.setId(UUID.fromString("33333333-4444-5555-6666-777777777777"));
        repository.setUrl("https://github.com/test/repo.git");
        repository.setBranch("main");
        execution.setRepository(repository);

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-virtual-key");
        stubSuccessfulExec();

        sandboxProvisioningService.launchAgent(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Exec> execCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, atLeastOnce()).request(eq(ENVIRONMENT_ID), execCaptor.capture());
        List<String> commands = execCaptor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .toList();
        assertThat(commands).contains("git rev-parse --verify HEAD");
        verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.LaunchAcpAgent.class));
    }

    @Test
    void launchAgent_withRepository_checkoutVerificationFailure_doesNotLaunchAgent() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENCODE, provider, "gpt-4o");
        Repository repository = new Repository();
        repository.setId(UUID.fromString("33333333-4444-5555-6666-777777777777"));
        repository.setUrl("https://github.com/test/repo.git");
        execution.setRepository(repository);

        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.Exec.class)))
                .thenReturn(new EnvironmentConnectorResult.Exec(ExecStatus.FAILED, 128, "not a git repository"));

        assertThatThrownBy(() -> sandboxProvisioningService.launchAgent(execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Repository checkout verification failed");

        verify(environmentRpcClient, never()).request(any(), any(EnvironmentRpcPayload.LaunchAcpAgent.class));
    }

    @Test
    void launchAgent_withoutRepository_doesNotVerifyCheckout() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENCODE, provider, "gpt-4o");

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-virtual-key");
        stubSuccessfulExec();

        sandboxProvisioningService.launchAgent(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Exec> execCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, atLeastOnce()).request(eq(ENVIRONMENT_ID), execCaptor.capture());
        List<String> commands = execCaptor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .toList();
        assertThat(commands).doesNotContain("git rev-parse --verify HEAD");
    }

    @Test
    void dispatchCheckout_sendsGitHeadlessEnvironmentVariables() throws Exception {
        SandboxExecution execution = createTestExecution();

        Repository repository = new Repository();
        repository.setId(UUID.fromString("33333333-4444-5555-6666-777777777777"));
        repository.setUrl("https://github.com/test/repo.git");
        repository.setBranch("develop");
        execution.setRepository(repository);

        sandboxProvisioningService.dispatchCheckout(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Checkout> paramsCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Checkout.class);
        verify(environmentRpcClient).send(eq(ENVIRONMENT_ID), paramsCaptor.capture());
        String json = objectMapper.writeValueAsString(paramsCaptor.getValue());
        assertThat(json).contains("GIT_TERMINAL_PROMPT");
        assertThat(json).contains("SSH_ASKPASS");
        assertThat(json).contains("https://github.com/test/repo.git");
        assertThat(json).contains("develop");
    }

    @Test
    void dispatchCheckout_withNullBranch_defaultsToMain() throws Exception {
        SandboxExecution execution = createTestExecution();

        Repository repository = new Repository();
        repository.setId(UUID.fromString("33333333-4444-5555-6666-777777777777"));
        repository.setUrl("https://github.com/test/repo.git");
        repository.setBranch(null);
        execution.setRepository(repository);

        sandboxProvisioningService.dispatchCheckout(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Checkout> paramsCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Checkout.class);
        verify(environmentRpcClient).send(eq(ENVIRONMENT_ID), paramsCaptor.capture());
        String json = objectMapper.writeValueAsString(paramsCaptor.getValue());
        assertThat(json).contains("\"main\"");
        assertThat(json).contains("GIT_TERMINAL_PROMPT");
        assertThat(json).contains("SSH_ASKPASS");
    }

    @Test
    void dispatchCheckout_registersGitAuthWithControlPlaneIdentity() throws Exception {
        SandboxExecution execution = createTestExecution();

        RepoCredential credential = new RepoCredential();
        credential.setType(CredentialType.PAT);
        Repository repository = new Repository();
        repository.setId(UUID.fromString("33333333-4444-5555-6666-777777777777"));
        repository.setUrl("https://github.com/test/repo.git");
        repository.setBranch("develop");
        repository.setCredential(credential);
        execution.setRepository(repository);

        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("pat-secret"));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.RegisterGitAuth.class)))
                .thenReturn(new EnvironmentConnectorResult.RegisterGitAuth(RegisterGitAuthStatus.SUCCESS));

        sandboxProvisioningService.dispatchCheckout(execution);

        ArgumentCaptor<EnvironmentRpcPayload.RegisterGitAuth> authCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.RegisterGitAuth.class);
        verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), authCaptor.capture());
        assertThat(authCaptor.getValue().userName()).isEqualTo("Kratis");
        assertThat(authCaptor.getValue().userEmail()).isEqualTo("kratis@control-plane.test");
    }

    @Test
    void completeAcpPrompt_withEndTurn_transitionsToIdle() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.END_TURN);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        assertThat(execution.getCompletedAt()).isNull();
        verify(sandboxExecutionRepository).save(execution);
    }

    @Test
    void completeAcpPrompt_withRefusal_marksFailed() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.REFUSAL);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(execution.getCompletedAt()).isNotNull();
        verify(sandboxExecutionRepository).save(execution);
    }

    @Test
    void completeAcpPrompt_withMaxTokens_transitionsToIdle() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.MAX_TOKENS);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        assertThat(execution.getCompletedAt()).isNull();
    }

    @Test
    void completeAcpPrompt_withMaxTurnRequests_transitionsToIdle() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.MAX_TURN_REQUESTS);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        assertThat(execution.getCompletedAt()).isNull();
    }

    @Test
    void completeAcpPrompt_withCancelled_transitionsToIdle() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.CANCELLED);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        assertThat(execution.getCompletedAt()).isNull();
    }

    @Test
    void completeAcpPrompt_withNullStopReason_transitionsToIdle() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), null);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        assertThat(execution.getCompletedAt()).isNull();
    }

    @Test
    void completeAcpPrompt_whenNotRunning_ignoresCall() {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.END_TURN);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        verify(sandboxExecutionRepository, never()).save(execution);
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionCompleteEvent.class));
    }

    @Test
    void completeAcpPrompt_withEndTurn_publishesExecutionStatusChangedEvent() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.END_TURN);

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
        assertThat(execution.getCompletedAt()).isNull();
        verify(sandboxExecutionRepository).save(execution);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ExecutionStatusChangedEvent statusEvent = (ExecutionStatusChangedEvent) eventCaptor.getValue();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(execution.getId());
    }

    @Test
    void completeAcpPrompt_withRefusal_publishesCompleteAndStatusChangedEvents() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.completeAcpPrompt(execution.getId(), StopReason.REFUSAL);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

        List<Object> events = eventCaptor.getAllValues();
        boolean hasCompleteEvent = events.stream().anyMatch(SandboxExecutionCompleteEvent.class::isInstance);
        boolean hasStatusChangedEvent = events.stream().anyMatch(ExecutionStatusChangedEvent.class::isInstance);

        assertThat(hasCompleteEvent).isTrue();
        assertThat(hasStatusChangedEvent).isTrue();

        ExecutionStatusChangedEvent statusEvent = events.stream()
                .filter(ExecutionStatusChangedEvent.class::isInstance)
                .map(ExecutionStatusChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(execution.getId());

        SandboxExecutionCompleteEvent completeEvent = events.stream()
                .filter(SandboxExecutionCompleteEvent.class::isInstance)
                .map(SandboxExecutionCompleteEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(completeEvent.status()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(completeEvent.executionId()).isEqualTo(execution.getId());
    }

    @Test
    void resumeAfterCheckout_afterCommit_launchesAgent() throws Exception {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENCODE, provider, "gpt-4o");

        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-virtual-key");
        stubSuccessfulExec();

        new TransactionTemplate(new ResourcelessTransactionManager())
                .executeWithoutResult(status -> sandboxExecutionService.resumeAfterCheckout(execution.getId()));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(environmentRpcClient)
                .request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.LaunchAcpAgent.class)));
    }

    @Test
    void dispatchAcpPrompt_success_sendsPromptWithTaskAndExecutionId() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setTaskPrompt("do the thing");
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(
                        new EnvironmentConnectorResult.AcpPrompt(PromptStatus.COMPLETED, StopReason.END_TURN, null));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<EnvironmentRpcPayload.AcpPrompt> captor =
                    ArgumentCaptor.forClass(EnvironmentRpcPayload.AcpPrompt.class);
            verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), captor.capture());
            assertThat(captor.getValue().taskPrompt()).isEqualTo("do the thing");
            assertThat(captor.getValue().executionId()).isEqualTo(EXECUTION_ID.toString());
            assertThat(captor.getValue().isSteering()).isNull();
        });
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionCompleteEvent.class));
    }

    @Test
    void dispatchAcpPrompt_promptFailedResponse_marksExecutionFailed() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(new EnvironmentConnectorResult.AcpPrompt(PromptStatus.FAILED, null, "no active session"));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues().stream()
                            .filter(SandboxExecutionCompleteEvent.class::isInstance)
                            .map(SandboxExecutionCompleteEvent.class::cast)
                            .anyMatch(e -> e.status() == SandboxExecutionStatus.FAILED))
                    .isTrue();
        });
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
    }

    @Test
    void dispatchSteeringPrompt_promptFailedResponse_keepsExecutionRunning() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(new EnvironmentConnectorResult.AcpPrompt(
                        PromptStatus.FAILED, null, "cannot transition to Prompting from state Prompting"));

        sandboxExecutionService.dispatchSteeringPrompt(execution, "steering guidance");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(environmentRpcClient)
                .request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)));
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionCompleteEvent.class));
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
    }

    @Test
    void dispatchSteeringPrompt_marksPayloadAsSteering() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(
                        new EnvironmentConnectorResult.AcpPrompt(PromptStatus.COMPLETED, StopReason.END_TURN, null));

        sandboxExecutionService.dispatchSteeringPrompt(execution, "steering guidance");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<EnvironmentRpcPayload.AcpPrompt> captor =
                    ArgumentCaptor.forClass(EnvironmentRpcPayload.AcpPrompt.class);
            verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), captor.capture());
            assertThat(captor.getValue().isSteering()).isEqualTo(Boolean.TRUE);
        });
    }

    @Test
    void dispatchAcpPrompt_rpcFailure_marksExecutionFailed() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(-32000, "session not connected"));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues().stream()
                            .filter(SandboxExecutionCompleteEvent.class::isInstance)
                            .map(SandboxExecutionCompleteEvent.class::cast)
                            .anyMatch(e -> e.status() == SandboxExecutionStatus.FAILED))
                    .isTrue();
        });
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
    }

    @Test
    void dispatchAcpPrompt_doesNotFailAlreadyCompletedExecution() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(-32000, "session not connected"));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(environmentRpcClient)
                .request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)));
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionCompleteEvent.class));
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.COMPLETED);
    }

    @Test
    void dispatchAcpPrompt_missingExecution_doesNotPublish() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.empty());
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenThrow(new EnvironmentRpcClient.EnvironmentRpcException(-32000, "session not connected"));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(environmentRpcClient)
                .request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)));
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionCompleteEvent.class));
    }

    @Test
    void dispatchAcpPrompt_requestTimeout_logsWithoutFailing() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenThrow(new java.util.concurrent.TimeoutException("agent still working"));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(environmentRpcClient)
                .request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)));
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionCompleteEvent.class));
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
    }

    @Test
    void dispatchAcpPrompt_unexpectedException_marksExecutionFailed() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenThrow(new IllegalStateException("unexpected"));

        sandboxExecutionService.dispatchAcpPrompt(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues().stream()
                            .filter(SandboxExecutionCompleteEvent.class::isInstance)
                            .map(SandboxExecutionCompleteEvent.class::cast)
                            .anyMatch(e -> e.status() == SandboxExecutionStatus.FAILED))
                    .isTrue();
        });
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
    }

    @Test
    void resumeAfterCheckout_withoutActiveTransaction_throws() {
        assertThatThrownBy(() -> sandboxExecutionService.resumeAfterCheckout(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumeAfterCheckout_launchFailure_marksExecutionFailedAndPublishes() {
        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(provider);

        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        new TransactionTemplate(new ResourcelessTransactionManager())
                .executeWithoutResult(status -> sandboxExecutionService.resumeAfterCheckout(execution.getId()));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

            List<Object> events = eventCaptor.getAllValues();
            boolean hasCompleteEvent = events.stream().anyMatch(SandboxExecutionCompleteEvent.class::isInstance);
            boolean hasStatusChangedEvent = events.stream().anyMatch(ExecutionStatusChangedEvent.class::isInstance);

            assertThat(hasCompleteEvent).isTrue();
            assertThat(hasStatusChangedEvent).isTrue();

            SandboxExecutionCompleteEvent completeEvent = events.stream()
                    .filter(SandboxExecutionCompleteEvent.class::isInstance)
                    .map(SandboxExecutionCompleteEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(completeEvent.status()).isEqualTo(SandboxExecutionStatus.FAILED);
            assertThat(completeEvent.executionId()).isEqualTo(execution.getId());
        });
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(execution.getCompletedAt()).isNotNull();
    }

    @Test
    void failExecution_marksExecutionFailedAndPublishes() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);

        sandboxExecutionService.failExecution(execution.getId());

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(execution.getCompletedAt()).isNotNull();
        verify(sandboxExecutionRepository).save(execution);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

        List<Object> events = eventCaptor.getAllValues();
        boolean hasCompleteEvent = events.stream().anyMatch(SandboxExecutionCompleteEvent.class::isInstance);
        boolean hasStatusChangedEvent = events.stream().anyMatch(ExecutionStatusChangedEvent.class::isInstance);

        assertThat(hasCompleteEvent).isTrue();
        assertThat(hasStatusChangedEvent).isTrue();

        ExecutionStatusChangedEvent statusEvent = events.stream()
                .filter(ExecutionStatusChangedEvent.class::isInstance)
                .map(ExecutionStatusChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(execution.getId());

        SandboxExecutionCompleteEvent completeEvent = events.stream()
                .filter(SandboxExecutionCompleteEvent.class::isInstance)
                .map(SandboxExecutionCompleteEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(completeEvent.status()).isEqualTo(SandboxExecutionStatus.FAILED);
        assertThat(completeEvent.executionId()).isEqualTo(execution.getId());
    }

    @Test
    void failExecution_executionNotFound_throws() {
        when(sandboxExecutionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sandboxExecutionService.failExecution(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);

        verify(sandboxExecutionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onSandboxExecutionCompleteEvent_finalizesUsageAndRevokesKey() {
        UUID teamId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SandboxExecution execution = createTestExecution();
        execution.setUsage(LlmUsage.withKey("sk-final-key"));
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.findByIdForUpdate(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(virtualKeyService.fetchUsage("sk-final-key")).thenReturn(new LlmUsageSnapshot(0.99, 2000L, 1200L, 800L));

        sandboxExecutionService.onSandboxExecutionCompleteEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), 0, SandboxExecutionStatus.COMPLETED));

        verify(virtualKeyService).fetchUsage("sk-final-key");
        verify(virtualKeyService).revokeKey("sk-final-key");
        verify(sandboxExecutionRepository).save(execution);
        assertThat(execution.getTotalSpend()).isEqualTo(0.99);
        assertThat(execution.getTotalTokens()).isEqualTo(2000L);
        assertThat(execution.getUsageLastUpdatedAt()).isNotNull();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ExecutionStatusChangedEvent statusEvent = (ExecutionStatusChangedEvent) eventCaptor.getValue();
        assertThat(statusEvent.teamId()).isEqualTo(teamId);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(execution.getId());
    }

    @Test
    void onSandboxExecutionCompleteEvent_fetchFailure_stillRevokesKey() {
        UUID teamId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SandboxExecution execution = createTestExecution();
        execution.setUsage(LlmUsage.withKey("sk-final-key"));
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(virtualKeyService.fetchUsage("sk-final-key")).thenThrow(new RuntimeException("litellm unreachable"));

        sandboxExecutionService.onSandboxExecutionCompleteEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), 0, SandboxExecutionStatus.COMPLETED));

        verify(virtualKeyService).revokeKey("sk-final-key");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onSandboxExecutionCompleteEvent_withoutVirtualKey_doesNothing() {
        UUID teamId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        sandboxExecutionService.onSandboxExecutionCompleteEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), 0, SandboxExecutionStatus.COMPLETED));

        verify(virtualKeyService, never()).fetchUsage(any());
        verify(virtualKeyService, never()).revokeKey(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onSandboxExecutionCompleteEvent_runsFinalizeAsynchronouslyOnExecutor() {
        List<Runnable> submitted = new java.util.ArrayList<>();
        Executor capturingExecutor = submitted::add;
        SandboxExecutionService asyncService = new SandboxExecutionService(
                transactionManager,
                sandboxExecutionRepository,
                chatRepository,
                executionEnvironmentRepository,
                environmentProviderRepository,
                teamMemberRepository,
                eventPublisher,
                sessionRegistry,
                canvasService,
                modelProviderRepository,
                sandboxProvisioningService,
                pendingHitlRegistry,
                virtualKeyService,
                environmentRpcClient,
                litellmProperties,
                capturingExecutor,
                activityPersistenceService);

        UUID teamId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SandboxExecution execution = createTestExecution();
        execution.setUsage(LlmUsage.withKey("sk-final-key"));
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.findByIdForUpdate(execution.getId())).thenReturn(Optional.of(execution));
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenReturn(execution);
        when(virtualKeyService.fetchUsage("sk-final-key")).thenReturn(new LlmUsageSnapshot(0.99, 2000L, 1200L, 800L));

        // The listener must hand the finalize to the executor (off the transport thread) rather than
        // running it synchronously, so a slow finalize can never block heartbeat/terminate handling.
        asyncService.onSandboxExecutionCompleteEvent(
                new SandboxExecutionCompleteEvent(teamId, execution.getId(), 0, SandboxExecutionStatus.COMPLETED));

        assertThat(submitted).hasSize(1);
        verify(virtualKeyService, never()).fetchUsage(any());
        verify(virtualKeyService, never()).revokeKey(any());

        submitted.getFirst().run();

        verify(virtualKeyService).fetchUsage("sk-final-key");
        verify(virtualKeyService).revokeKey("sk-final-key");
        assertThat(execution.getTotalTokens()).isEqualTo(2000L);
    }

    @Test
    void launchAgent_usesSandboxBaseUrlForContainerInjection() throws Exception {
        LiteLLMProperties litellmProperties = new LiteLLMProperties();
        litellmProperties.setBaseUrl("http://localhost:4000");
        litellmProperties.setSandboxBaseUrl("http://host.docker.internal:4000");
        SandboxProvisioningService provisioningService = new SandboxProvisioningService(
                transactionManager,
                sandboxOrchestratorService,
                executionEnvironmentRepository,
                sandboxExecutionRepository,
                credentialResolver,
                virtualKeyService,
                litellmProvisioningService,
                litellmProperties,
                environmentRpcClient,
                createKratisProperties());

        ModelProvider provider = createTestModelProvider();
        SandboxExecution execution = createTestExecution(AgentHarness.OPENHANDS, provider, "gpt-4o");

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        when(litellmProvisioningService.buildVirtualKeyAlias(any(), any())).thenReturn("test-alias");
        when(virtualKeyService.generateKey(any(), any())).thenReturn("sk-virtual-key");
        stubSuccessfulExec();

        provisioningService.launchAgent(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Exec> execCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, atLeastOnce()).request(eq(ENVIRONMENT_ID), execCaptor.capture());
        String allExecCommands = execCaptor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .reduce("", (a, b) -> a + "\n" + b);
        // Sandbox containers reach the host via host.docker.internal, never via the host-local URL
        assertThat(allExecCommands).contains("http://host.docker.internal:4000");
        assertThat(allExecCommands).doesNotContain("http://localhost:4000");
    }

    @Test
    void launchAgent_shouldNotExposeModelProviderBaseUrl() throws Exception {
        ModelProvider provider = createTestModelProvider();
        provider.setBaseUrl("http://localhost:8080/v1");
        SandboxExecution execution = createTestExecution(AgentHarness.OPENHANDS, provider, "gpt-4o");

        when(litellmProvisioningService.buildLiteLLMModelName(provider, "gpt-4o"))
                .thenReturn("openai-test-provider-gpt-4o-11111111");
        when(litellmProvisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .thenReturn(true);
        stubSuccessfulExec();

        sandboxProvisioningService.launchAgent(execution);

        ArgumentCaptor<EnvironmentRpcPayload.Exec> execCaptor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, atLeastOnce()).request(eq(ENVIRONMENT_ID), execCaptor.capture());
        String allExecCommands = execCaptor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allExecCommands).doesNotContain("http://localhost:8080/v1");
        assertThat(allExecCommands).doesNotContain("${MODEL_PROVIDER_BASE_URL}");
        assertThat(allExecCommands).contains("http://localhost:4000");
    }

    @Test
    void terminateExecution_withPendingPermission_removesPermissionAndSendsRejection() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sessionRegistry.getSessionForEnvironment(execution.getEnvironment().getId()))
                .thenReturn(webSocketSession);
        when(webSocketSession.isOpen()).thenReturn(true);

        PendingHitlRegistry.PendingHitl pendingHitl = new PendingHitlRegistry.PendingHitl(
                new ExecutionHitlRequiredResult(
                        execution.getId(),
                        "tool-call-1",
                        HitlKind.APPROVAL,
                        "Approve rm -rf /",
                        "rm -rf /",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                webSocketSession,
                "req-123",
                Instant.now(),
                UUID.randomUUID());
        when(pendingHitlRegistry.remove(execution.getId())).thenReturn(pendingHitl);

        sandboxExecutionService.terminateExecution(execution.getId());

        verify(pendingHitlRegistry).remove(execution.getId());
        verify(environmentRpcClient).replyError(eq(ENVIRONMENT_ID), eq("req-123"), any(JsonRpcError.class));

        ArgumentCaptor<SandboxExecutionHitlResolvedEvent> hitlCaptor =
                ArgumentCaptor.forClass(SandboxExecutionHitlResolvedEvent.class);
        verify(eventPublisher).publishEvent(hitlCaptor.capture());
        assertThat(hitlCaptor.getValue().result().executionId()).isEqualTo(execution.getId());
        assertThat(hitlCaptor.getValue().result().response()).isEqualTo(HitlResponse.CANCELLED);
        assertThat(hitlCaptor.getValue().result().kind()).isEqualTo(HitlKind.APPROVAL);

        verify(environmentRpcClient)
                .request(
                        eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.Terminate.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void terminateExecution_withoutPendingPermission_sendsOnlyTerminate() throws Exception {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(sessionRegistry.getSessionForEnvironment(execution.getEnvironment().getId()))
                .thenReturn(webSocketSession);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(pendingHitlRegistry.remove(execution.getId())).thenReturn(null);

        sandboxExecutionService.terminateExecution(execution.getId());

        verify(pendingHitlRegistry).remove(execution.getId());
        verify(environmentRpcClient, never()).replyError(any(), any(), any());
        verify(environmentRpcClient)
                .request(
                        eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.Terminate.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void failExecution_withPendingPermission_publishesCancelledResolution() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        PendingHitlRegistry.PendingHitl pendingHitl = new PendingHitlRegistry.PendingHitl(
                new ExecutionHitlRequiredResult(
                        execution.getId(),
                        "hitl-789",
                        HitlKind.QUESTION,
                        "Pick a target",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("type", "object")),
                webSocketSession,
                "req-456",
                Instant.now(),
                TEAM_ID);
        when(pendingHitlRegistry.remove(execution.getId())).thenReturn(pendingHitl);

        sandboxExecutionService.failExecution(execution.getId());

        verify(pendingHitlRegistry).remove(execution.getId());
        verify(environmentRpcClient).replyError(eq(ENVIRONMENT_ID), eq("req-456"), any(JsonRpcError.class));

        ArgumentCaptor<SandboxExecutionHitlResolvedEvent> hitlCaptor =
                ArgumentCaptor.forClass(SandboxExecutionHitlResolvedEvent.class);
        verify(eventPublisher).publishEvent(hitlCaptor.capture());
        assertThat(hitlCaptor.getValue().result().executionId()).isEqualTo(execution.getId());
        assertThat(hitlCaptor.getValue().result().hitlId()).isEqualTo("hitl-789");
        assertThat(hitlCaptor.getValue().result().kind()).isEqualTo(HitlKind.QUESTION);
        assertThat(hitlCaptor.getValue().result().response()).isEqualTo(HitlResponse.CANCELLED);
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
    }

    @Test
    void failExecution_withoutPendingPermission_doesNotPublishHitlResolution() {
        SandboxExecution execution = createTestExecution();
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(pendingHitlRegistry.remove(execution.getId())).thenReturn(null);

        sandboxExecutionService.failExecution(execution.getId());

        verify(environmentRpcClient, never()).replyError(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(SandboxExecutionHitlResolvedEvent.class));
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.FAILED);
    }

    private void stubCreationDependencies() {
        Team team = new Team();
        team.setId(TEAM_ID);

        ChatEntity chat = new ChatEntity();
        chat.setId(CHAT_ID);
        chat.setTeam(team);

        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(chat));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(sandboxExecutionRepository.save(any(SandboxExecution.class))).thenAnswer(inv -> {
            SandboxExecution execution = inv.getArgument(0);
            if (execution.getId() == null) {
                execution.setId(EXECUTION_ID);
            }
            return execution;
        });

        ModelProvider provider = createTestModelProvider();
        when(modelProviderRepository.findByTeamIdAndId(TEAM_ID, provider.getId()))
                .thenReturn(Optional.of(provider));
    }

    private CanvasEntity specCanvasWithRepository() {
        CanvasEntity canvas = new CanvasEntity();
        canvas.setCanvasType(CanvasType.SPEC);
        Repository repository = new Repository();
        repository.setId(UUID.fromString("33333333-4444-5555-6666-777777777777"));
        canvas.setRepository(repository);
        return canvas;
    }

    @Test
    void createExecution_withExistingEnvironment_publishesStatusChangedEvent() {
        stubCreationDependencies();

        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(ENVIRONMENT_ID);
        environment.setTeam(new Team());
        when(executionEnvironmentRepository.findByTeamIdAndId(TEAM_ID, ENVIRONMENT_ID))
                .thenReturn(Optional.of(environment));
        when(canvasService.getCanvas(CHAT_ID, "plan-doc")).thenReturn(specCanvasWithRepository());

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                null,
                ENVIRONMENT_ID,
                AgentHarness.OPENCODE,
                "plan-doc",
                createTestModelProvider().getId(),
                "gpt-4o");

        SandboxExecutionDto dto = sandboxExecutionService.createExecution(USER_ID, CHAT_ID, request);

        assertThat(dto.status()).isEqualTo(SandboxExecutionStatus.RUNNING);
        assertThat(dto.chatId()).isEqualTo(CHAT_ID);
        assertThat(dto.id()).isEqualTo(EXECUTION_ID);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ExecutionStatusChangedEvent statusEvent = (ExecutionStatusChangedEvent) eventCaptor.getValue();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(EXECUTION_ID);
    }

    @Test
    void createExecution_withProviderId_publishesStatusChangedEvent() {
        stubCreationDependencies();

        Team team = new Team();
        team.setId(TEAM_ID);
        EnvironmentProvider envProvider = new EnvironmentProvider();
        envProvider.setId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        envProvider.setTeam(team);
        envProvider.setName("Test Provider");
        when(environmentProviderRepository.findById(envProvider.getId())).thenReturn(Optional.of(envProvider));
        when(canvasService.getCanvas(CHAT_ID, "plan-doc")).thenReturn(specCanvasWithRepository());
        when(executionEnvironmentRepository.save(any(ExecutionEnvironment.class)))
                .thenAnswer(inv -> {
                    ExecutionEnvironment env = inv.getArgument(0);
                    if (env.getId() == null) {
                        env.setId(UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444"));
                    }
                    return env;
                });
        when(executionEnvironmentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ExecutionEnvironment env = new ExecutionEnvironment();
            env.setId(inv.getArgument(0));
            return Optional.of(env);
        });
        when(sandboxOrchestratorService.getProvider(any(ExecutionProviderType.class)))
                .thenReturn(sandboxProvider);
        when(sandboxProvider.spawnSandbox(any(ExecutionEnvironment.class), any(String.class)))
                .thenReturn("container-1");

        CreateSandboxExecutionRequest request = new CreateSandboxExecutionRequest(
                envProvider.getId(),
                null,
                AgentHarness.OPENCODE,
                "plan-doc",
                createTestModelProvider().getId(),
                "gpt-4o");

        SandboxExecutionDto dto = sandboxExecutionService.createExecution(USER_ID, CHAT_ID, request);

        assertThat(dto.status()).isEqualTo(SandboxExecutionStatus.RUNNING);
        assertThat(dto.chatId()).isEqualTo(CHAT_ID);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());
        ExecutionStatusChangedEvent statusEvent = eventCaptor.getAllValues().stream()
                .filter(ExecutionStatusChangedEvent.class::isInstance)
                .map(ExecutionStatusChangedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(EXECUTION_ID);
    }

    @Test
    void toDto_mapsAllFields() {
        SandboxExecution execution = createTestExecution();

        SandboxExecutionDto dto = sandboxExecutionService.toDto(execution);

        assertThat(dto.id()).isEqualTo(execution.getId());
        assertThat(dto.chatId()).isEqualTo(CHAT_ID);
        assertThat(dto.status()).isEqualTo(SandboxExecutionStatus.RUNNING);
    }

    @Test
    void steerExecution_fromIdle_transitionsToRunningAndPublishesStatusChangedEvent() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.IDLE);
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(execution.getChat()));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(
                        new EnvironmentConnectorResult.AcpPrompt(PromptStatus.COMPLETED, StopReason.END_TURN, null));

        sandboxExecutionService.steerExecution(
                USER_ID, CHAT_ID, execution.getId(), new SteerExecutionRequest("Fix tests", List.of()));

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        verify(sandboxExecutionRepository).save(execution);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ExecutionStatusChangedEvent statusEvent = (ExecutionStatusChangedEvent) eventCaptor.getValue();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(execution.getId());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(environmentRpcClient)
                .request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)));
    }

    @Test
    void steerExecution_fromFailed_relaunchesOnFreshSessionWithRecoveryPrefix() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.FAILED);
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(execution.getChat()));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(
                        new EnvironmentConnectorResult.AcpPrompt(PromptStatus.COMPLETED, StopReason.END_TURN, null));

        sandboxExecutionService.steerExecution(
                USER_ID, CHAT_ID, execution.getId(), new SteerExecutionRequest("Fix tests", List.of()));

        // Recovery: the execution is running again and the prompt is dispatched on a
        // freshly relaunched agent session (relaunch=true, normal turn).
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        verify(sandboxExecutionRepository).save(execution);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<EnvironmentRpcPayload.AcpPrompt> captor =
                    ArgumentCaptor.forClass(EnvironmentRpcPayload.AcpPrompt.class);
            verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), captor.capture());
            EnvironmentRpcPayload.AcpPrompt prompt = captor.getValue();
            assertThat(prompt.relaunch()).isEqualTo(Boolean.TRUE);
            assertThat(prompt.isSteering()).isNull();
            assertThat(prompt.taskPrompt()).startsWith(SandboxExecutionService.RECOVERY_PROMPT_PREFIX);
            assertThat(prompt.taskPrompt()).endsWith("Fix tests");
        });
    }

    @Test
    void steerExecution_fromRunning_dispatchesOrdinarySteeringWithoutRelaunch() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(execution.getChat()));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(
                        new EnvironmentConnectorResult.AcpPrompt(PromptStatus.COMPLETED, StopReason.END_TURN, null));

        sandboxExecutionService.steerExecution(
                USER_ID, CHAT_ID, execution.getId(), new SteerExecutionRequest("Also fix lint", List.of()));

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<EnvironmentRpcPayload.AcpPrompt> captor =
                    ArgumentCaptor.forClass(EnvironmentRpcPayload.AcpPrompt.class);
            verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), captor.capture());
            EnvironmentRpcPayload.AcpPrompt prompt = captor.getValue();
            assertThat(prompt.relaunch()).isNull();
            assertThat(prompt.isSteering()).isEqualTo(Boolean.TRUE);
            assertThat(prompt.taskPrompt()).isEqualTo("Also fix lint");
        });
    }

    @Test
    void steerExecution_fromCompleted_isRejected() {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(execution.getChat()));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(true);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> sandboxExecutionService.steerExecution(
                        USER_ID, CHAT_ID, execution.getId(), new SteerExecutionRequest("nope", List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot steer execution in state COMPLETED");
    }

    @Test
    void dispatchSystemSteering_fromIdle_revivesExecutionAndDispatchesSteeringPrompt() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.IDLE);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.AcpPrompt.class)))
                .thenReturn(
                        new EnvironmentConnectorResult.AcpPrompt(PromptStatus.COMPLETED, StopReason.END_TURN, null));

        sandboxExecutionService.dispatchSystemSteering(execution.getId(), "no response was received");

        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        verify(sandboxExecutionRepository).save(execution);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ExecutionStatusChangedEvent statusEvent = (ExecutionStatusChangedEvent) eventCaptor.getValue();
        assertThat(statusEvent.teamId()).isEqualTo(TEAM_ID);
        assertThat(statusEvent.chatId()).isEqualTo(CHAT_ID);
        assertThat(statusEvent.executionId()).isEqualTo(execution.getId());

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            ArgumentCaptor<EnvironmentRpcPayload.AcpPrompt> captor =
                    ArgumentCaptor.forClass(EnvironmentRpcPayload.AcpPrompt.class);
            verify(environmentRpcClient).request(eq(ENVIRONMENT_ID), captor.capture());
            assertThat(captor.getValue().taskPrompt()).isEqualTo("no response was received");
            assertThat(captor.getValue().isSteering()).isEqualTo(Boolean.TRUE);
        });
    }

    @Test
    void dispatchSystemSteering_terminalExecution_skipsDispatch() throws Exception {
        SandboxExecution execution = createTestExecution();
        execution.setStatus(SandboxExecutionStatus.FAILED);
        when(sandboxExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        sandboxExecutionService.dispatchSystemSteering(execution.getId(), "guidance");

        verify(sandboxExecutionRepository, never()).save(any());
        verify(environmentRpcClient, never()).request(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void dispatchSystemSteering_missingExecution_skipsDispatch() throws Exception {
        when(sandboxExecutionRepository.findById(EXECUTION_ID)).thenReturn(Optional.empty());

        sandboxExecutionService.dispatchSystemSteering(EXECUTION_ID, "guidance");

        verify(sandboxExecutionRepository, never()).save(any());
        verify(environmentRpcClient, never()).request(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
