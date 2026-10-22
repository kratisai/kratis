package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.config.PlanningProperties;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.LlmUsage;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ChatUsageSessionService;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ScratchpadService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class PlanningAgentServiceTest {

    private ModelProviderRepository modelProviderRepository;
    private ChatModelFactory chatModelFactory;
    private ChatService chatService;
    private ChatUsageSessionService chatUsageSessionService;
    private LiteLLMProvisioningService liteLLMProvisioningService;
    private ChatTitleService chatTitleService;
    private ChatMemory chatMemory;
    private CanvasTool canvasTool;
    private RepositoryTool repositoryTool;
    private DimensionTool dimensionTool;
    private WikiTool wikiTool;
    private ScratchpadTool scratchpadTool;
    private WebSearchTool webSearchTool;
    private ScratchpadService scratchpadService;
    private PlanningProperties planningProperties;
    private AsyncTaskExecutor agentTaskExecutor;
    private PlanningAgentService planningAgentService;

    @BeforeEach
    void setUp() {
        modelProviderRepository = mock(ModelProviderRepository.class);
        chatModelFactory = mock(ChatModelFactory.class);
        chatService = mock(ChatService.class);
        chatUsageSessionService = mock(ChatUsageSessionService.class);
        liteLLMProvisioningService = mock(LiteLLMProvisioningService.class);
        chatTitleService = mock(ChatTitleService.class);
        chatMemory = mock(ChatMemory.class);
        canvasTool = mock(CanvasTool.class);
        repositoryTool = mock(RepositoryTool.class);
        dimensionTool = mock(DimensionTool.class);
        wikiTool = mock(WikiTool.class);
        scratchpadTool = mock(ScratchpadTool.class);
        webSearchTool = mock(WebSearchTool.class);
        scratchpadService = mock(ScratchpadService.class);
        planningProperties = new PlanningProperties();
        agentTaskExecutor = new SimpleAsyncTaskExecutor("test-agent-");

        planningAgentService = new PlanningAgentService(
                modelProviderRepository,
                chatModelFactory,
                chatService,
                chatUsageSessionService,
                liteLLMProvisioningService,
                chatTitleService,
                chatMemory,
                canvasTool,
                repositoryTool,
                dimensionTool,
                wikiTool,
                scratchpadTool,
                webSearchTool,
                scratchpadService,
                planningProperties,
                agentTaskExecutor);
    }

    @Test
    void replaySink_replaysAllItemsToLateSubscribers() {
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        UUID chatId = UUID.randomUUID();

        // Emit item 1 and item 2 before any subscriber
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "m1", 1, "Hello "));
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "m1", 1, "world!"));

        // First late subscriber
        List<ClientPayload.ChatStreamPayload> list1 = new ArrayList<>();
        flux.subscribe(list1::add);

        // Emit item 3
        sink.tryEmitNext(new ClientPayload.CompleteResult("m1"));
        sink.tryEmitComplete();

        // Second late subscriber (subscribes after completion)
        List<ClientPayload.ChatStreamPayload> list2 = new ArrayList<>();
        flux.subscribe(list2::add);

        assertThat(list1).hasSize(3);
        assertThat(list2).hasSize(3);

        assertThat(((ClientPayload.MessageChunkResult) list1.get(0)).content()).isEqualTo("Hello ");
        assertThat(((ClientPayload.MessageChunkResult) list1.get(1)).content()).isEqualTo("world!");
        assertThat(list1.get(2)).isInstanceOf(ClientPayload.CompleteResult.class);

        assertThat(((ClientPayload.MessageChunkResult) list2.get(0)).content()).isEqualTo("Hello ");
        assertThat(((ClientPayload.MessageChunkResult) list2.get(1)).content()).isEqualTo("world!");
        assertThat(list2.get(2)).isInstanceOf(ClientPayload.CompleteResult.class);
    }

    @Test
    void streamMessage_cancellingFluxCancelsExecutionAndDoesNotPersistError() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();

        ModelProvider provider = new ModelProvider("TestProvider", ProviderType.OPENAI, "key", "url");
        when(modelProviderRepository.findByTeamIdAndId(teamId, providerId)).thenReturn(Optional.of(provider));
        when(liteLLMProvisioningService.buildLiteLLMModelName(any(), any())).thenReturn("litellm-model");

        ChatUsageSession session = mock(ChatUsageSession.class);
        LlmUsage usage = mock(LlmUsage.class);
        when(usage.getVirtualKey()).thenReturn("vk-123");
        when(session.getUsage()).thenReturn(usage);
        when(chatUsageSessionService.getOrCreateActiveSession(chatId, "gpt-4o", "litellm-model"))
                .thenReturn(session);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModelFactory.createChatModelViaLiteLLM(any(), any(), any(), eq(true)))
                .thenReturn(chatModel);

        // Mock orchestrationLoop inside planningAgentService
        CountDownLatch agentStartedLatch = new CountDownLatch(1);
        CountDownLatch agentCancelledLatch = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        PlanningAgentLoop mockLoop = mock(PlanningAgentLoop.class);
        org.mockito.Mockito.doAnswer(invocation -> {
                    agentStartedLatch.countDown();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        wasInterrupted.set(true);
                        agentCancelledLatch.countDown();
                        throw e;
                    }
                    return null;
                })
                .when(mockLoop)
                .run(any(), any(), any());

        Field orchestrationLoopField = PlanningAgentService.class.getDeclaredField("orchestrationLoop");
        orchestrationLoopField.setAccessible(true);
        orchestrationLoopField.set(planningAgentService, mockLoop);

        Flux<ClientPayload.ChatStreamPayload> flux =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "Hello");

        Disposable subscription = flux.subscribe();

        // Wait for agent loop to start running
        assertThat(agentStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Cancel the subscription
        subscription.dispose();

        // Wait for task cancellation interrupt
        assertThat(agentCancelledLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(wasInterrupted.get()).isTrue();

        // The interrupt reaches the worker before its finally block runs, so wait for the usage
        // sync that finally performs before asserting the terminal state.
        verify(chatUsageSessionService, timeout(5000)).syncUsage(chatId, "vk-123");

        // Once the worker has finished, the error must NOT have been persisted to DB
        verify(chatService, never()).saveAssistantErrorMessage(any(), anyString(), anyInt());

        // Verify task was removed from activeAgentTasks
        Field tasksField = PlanningAgentService.class.getDeclaredField("activeAgentTasks");
        tasksField.setAccessible(true);
        @SuppressWarnings("rawtypes")
        Map activeTasks = (Map) tasksField.get(planningAgentService);
        assertThat(activeTasks).doesNotContainKey(chatId);
    }

    @Test
    void cancelExecution_cancelsFutureDirectly() throws Exception {
        UUID chatId = UUID.randomUUID();
        Future<?> mockFuture = mock(Future.class);

        Field tasksField = PlanningAgentService.class.getDeclaredField("activeAgentTasks");
        tasksField.setAccessible(true);
        @SuppressWarnings("rawtypes")
        Map activeTasks = (Map) tasksField.get(planningAgentService);
        activeTasks.put(chatId, mockFuture);

        planningAgentService.cancelExecution(chatId);

        verify(mockFuture).cancel(true);
        assertThat(activeTasks).doesNotContainKey(chatId);
    }

    @Test
    void continuePlanning_doesNotCallSaveUserMessage_andInvokesLoopWithDirective() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();

        ModelProvider provider = new ModelProvider("TestProvider", ProviderType.OPENAI, "key", "url");
        when(modelProviderRepository.findByTeamIdAndId(teamId, providerId)).thenReturn(Optional.of(provider));
        when(liteLLMProvisioningService.buildLiteLLMModelName(any(), any())).thenReturn("litellm-model");

        ChatUsageSession session = mock(ChatUsageSession.class);
        LlmUsage usage = mock(LlmUsage.class);
        when(usage.getVirtualKey()).thenReturn("vk-123");
        when(session.getUsage()).thenReturn(usage);
        when(chatUsageSessionService.getOrCreateActiveSession(chatId, "gpt-4o", "litellm-model"))
                .thenReturn(session);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModelFactory.createChatModelViaLiteLLM(any(), any(), any(), eq(true)))
                .thenReturn(chatModel);

        PlanningAgentLoop mockLoop = mock(PlanningAgentLoop.class);
        Field orchestrationLoopField = PlanningAgentService.class.getDeclaredField("orchestrationLoop");
        orchestrationLoopField.setAccessible(true);
        orchestrationLoopField.set(planningAgentService, mockLoop);

        CountDownLatch completedLatch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
                    completedLatch.countDown();
                    return null;
                })
                .when(mockLoop)
                .run(any(), any(), any());

        Flux<ClientPayload.ChatStreamPayload> flux =
                planningAgentService.continuePlanning(teamId, providerId, "gpt-4o", chatId, "directive-123");

        flux.subscribe();

        assertThat(completedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        verify(chatService, never()).saveUserMessage(any(), anyString());
        verify(mockLoop).run(any(), any(), any());
    }

    @Test
    void continuePlanning_nullDirective_throws() {
        assertThatThrownBy(() -> nullDirectiveContinue(planningAgentService)).isInstanceOf(NullPointerException.class);
    }

    @SuppressFBWarnings("NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS")
    private static void nullDirectiveContinue(PlanningAgentService service) {
        service.continuePlanning(null, null, null, null, null);
    }

    @Test
    void execute_reactLoopExhausted_emitsPlanningStatusAndSavesInterstitial() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();

        ModelProvider provider = new ModelProvider("TestProvider", ProviderType.OPENAI, "key", "url");
        when(modelProviderRepository.findByTeamIdAndId(teamId, providerId)).thenReturn(Optional.of(provider));
        when(liteLLMProvisioningService.buildLiteLLMModelName(any(), any())).thenReturn("litellm-model");

        ChatUsageSession session = mock(ChatUsageSession.class);
        LlmUsage usage = mock(LlmUsage.class);
        when(usage.getVirtualKey()).thenReturn("vk-123");
        when(session.getUsage()).thenReturn(usage);
        when(chatUsageSessionService.getOrCreateActiveSession(chatId, "gpt-4o", "litellm-model"))
                .thenReturn(session);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModelFactory.createChatModelViaLiteLLM(any(), any(), any(), eq(true)))
                .thenReturn(chatModel);

        PlanningAgentLoop mockLoop = mock(PlanningAgentLoop.class);
        org.mockito.Mockito.doThrow(
                        new com.kratisai.controlplane.agentloop.ReActLoopExhaustedException(10, null, List.of()))
                .when(mockLoop)
                .run(any(), any(), any());

        Field orchestrationLoopField = PlanningAgentService.class.getDeclaredField("orchestrationLoop");
        orchestrationLoopField.setAccessible(true);
        orchestrationLoopField.set(planningAgentService, mockLoop);

        Flux<ClientPayload.ChatStreamPayload> flux =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "Hello");

        List<ClientPayload.ChatStreamPayload> received = new ArrayList<>();
        CountDownLatch completeLatch = new CountDownLatch(1);
        flux.subscribe(received::add, error -> completeLatch.countDown(), completeLatch::countDown);

        assertThat(completeLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(ClientPayload.PlanningStatusResult.class);
        ClientPayload.PlanningStatusResult status = (ClientPayload.PlanningStatusResult) received.get(0);
        assertThat(status.status()).isEqualTo("paused");
        assertThat(status.reason()).isEqualTo("max_iterations");

        verify(chatService).saveInterstitialMessage(eq(chatId), eq("paused"), eq("max_iterations"), any());
        verify(chatService, never()).saveAssistantErrorMessage(any(), anyString(), anyInt());
    }
}
