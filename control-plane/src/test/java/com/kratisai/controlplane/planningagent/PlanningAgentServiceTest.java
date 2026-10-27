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
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ChatUsageSessionService;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ScratchpadService;
import com.kratisai.controlplane.testsupport.llm.FakeChatModel;
import com.kratisai.controlplane.testsupport.llm.PromptMatcher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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
    private ChatFluxRegistry chatFluxRegistry;
    private PlanningAgentService planningAgentService;

    @BeforeEach
    void setUp() {
        modelProviderRepository = mock(ModelProviderRepository.class);
        chatModelFactory = mock(ChatModelFactory.class);
        chatService = mock(ChatService.class);
        chatUsageSessionService = mock(ChatUsageSessionService.class);
        liteLLMProvisioningService = mock(LiteLLMProvisioningService.class);
        chatFluxRegistry = new ChatFluxRegistry();
        AsyncTaskExecutor agentTaskExecutor = new SimpleAsyncTaskExecutor("test-agent-");

        planningAgentService = new PlanningAgentService(
                modelProviderRepository,
                chatModelFactory,
                chatService,
                chatUsageSessionService,
                liteLLMProvisioningService,
                mock(ChatTitleService.class),
                mock(ChatMemory.class),
                mock(CanvasTool.class),
                mock(RepositoryTool.class),
                mock(DimensionTool.class),
                mock(WikiTool.class),
                mock(ScratchpadTool.class),
                mock(WebSearchTool.class),
                mock(ScratchpadService.class),
                new PlanningProperties(),
                chatFluxRegistry,
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
    void streamMessage_clientDisconnect_keepsRunningAndReplaysForReconnect() throws Exception {
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

        CountDownLatch agentStarted = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);

        PlanningAgentLoop mockLoop = mock(PlanningAgentLoop.class);
        org.mockito.Mockito.doAnswer(invocation -> {
                    Sinks.Many<ClientPayload.ChatStreamPayload> loopSink = invocation.getArgument(2);
                    loopSink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", 1, "Chunk 1 "));
                    agentStarted.countDown();
                    assertThat(releaseAgent.await(3, TimeUnit.SECONDS)).isTrue();
                    loopSink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "msg-1", 1, "Chunk 2"));
                    return null;
                })
                .when(mockLoop)
                .run(any(), any(), any());

        Field orchestrationLoopField = PlanningAgentService.class.getDeclaredField("orchestrationLoop");
        orchestrationLoopField.setAccessible(true);
        orchestrationLoopField.set(planningAgentService, mockLoop);

        Flux<ClientPayload.ChatStreamPayload> flux =
                planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "Hello");

        Disposable firstSubscription = flux.subscribe();
        assertThat(agentStarted.await(3, TimeUnit.SECONDS)).isTrue();

        firstSubscription.dispose();

        assertThat(chatFluxRegistry.containsKey(chatId)).isTrue();

        List<ClientPayload.ChatStreamPayload> reconnected = new ArrayList<>();
        CountDownLatch reconnectedComplete = new CountDownLatch(1);
        flux.subscribe(reconnected::add, error -> reconnectedComplete.countDown(), reconnectedComplete::countDown);

        assertThat(reconnected)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult chunk
                        && chunk.content().equals("Chunk 1 "));

        releaseAgent.countDown();

        assertThat(reconnectedComplete.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(reconnected)
                .anyMatch(p -> p instanceof ClientPayload.MessageChunkResult chunk
                        && chunk.content().equals("Chunk 2"));
        assertThat(reconnected.getLast()).isInstanceOf(ClientPayload.CompleteResult.class);

        verify(chatUsageSessionService, timeout(5000)).syncUsage(chatId, "vk-123");
        verify(chatService, never()).saveAssistantErrorMessage(any(), anyString(), anyInt());
        assertThat(chatFluxRegistry.containsKey(chatId)).isFalse();
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
        assertThat(received.getFirst()).isInstanceOf(ClientPayload.PlanningStatusResult.class);
        ClientPayload.PlanningStatusResult status = (ClientPayload.PlanningStatusResult) received.getFirst();
        assertThat(status.status()).isEqualTo("paused");
        assertThat(status.reason()).isEqualTo("max_iterations");

        verify(chatService).saveInterstitialMessage(eq(chatId), eq("paused"), eq("max_iterations"), any());
        verify(chatService, never()).saveAssistantErrorMessage(any(), anyString(), anyInt());
    }

    @Test
    void evaluateContinuationDirective_sendsToolResultsInPlainTranscript() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder()
                .response("Use the AuthService finding and finish the plan.")
                .build());
        ChatClient chatClient = ChatClient.builder(model).build();

        List<Message> history = List.of(
                new SystemMessage("planning system prompt"),
                new UserMessage("Find the authentication bug"),
                AssistantMessage.builder()
                        .content("Searching the repository.")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "search_repository", "{\"query\":\"authentication\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-1", "search_repository", "AuthService.java:42 drops the token")))
                        .build());

        String directive = planningAgentService.evaluateContinuationDirective(chatClient, history);

        assertThat(directive).isEqualTo("Use the AuthService finding and finish the plan.");
        Prompt prompt = model.getInvocations().getLast().prompt();
        assertThat(prompt.getInstructions()).hasSize(2);
        assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        Message transcriptMessage = prompt.getInstructions().get(1);
        assertThat(transcriptMessage).isInstanceOf(UserMessage.class);
        assertThat(transcriptMessage.getText())
                .contains("Find the authentication bug")
                .contains("search_repository")
                .contains("AuthService.java:42 drops the token");
        assertThat(prompt.getInstructions()).noneMatch(AssistantMessage.class::isInstance);
        assertThat(prompt.getInstructions()).noneMatch(ToolResponseMessage.class::isInstance);
    }

    @Test
    void evaluateContinuationDirective_blankLlmResponseFallsBackToDefault() {
        FakeChatModel model = new FakeChatModel();
        model.addMatcher(PromptMatcher.builder().response("   ").build());
        ChatClient chatClient = ChatClient.builder(model).build();

        String directive = planningAgentService.evaluateContinuationDirective(
                chatClient, List.of(new UserMessage("Find the authentication bug")));

        assertThat(directive).isEqualTo(PlanningAgentService.DEFAULT_CONTINUATION_DIRECTIVE);
    }

    @Test
    void formatContinuationTranscript_keepsRecentWorkAndStaysWithinLimits() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("Investigate the bug"));
        for (int i = 0; i < 100; i++) {
            history.add(new AssistantMessage("step-" + i));
        }

        String transcript = PlanningAgentService.formatContinuationTranscript(history);

        assertThat(transcript).contains("TURN REQUEST:\nInvestigate the bug");
        assertThat(transcript).doesNotContain("step-59");
        assertThat(transcript).contains("step-60").contains("step-99");
        assertThat(PlanningAgentService.formatContinuationTranscript(null)).isEmpty();
        assertThat(PlanningAgentService.formatContinuationTranscript(List.of())).isEmpty();
    }
}
