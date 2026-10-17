package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.planningagent.PlanningAgentService;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ClientChatContinueRpcHandlerTest {

    private PlanningAgentService planningAgentService;
    private ChatService chatService;
    private ClientSessionRegistry sessionRegistry;
    private TeamMemberRepository teamMemberRepository;
    private ChatFluxRegistry chatFluxRegistry;
    private ModelProviderRepository modelProviderRepository;
    private ChatUsageSessionRepository chatUsageSessionRepository;
    private ClientChatContinueRpcHandler handler;

    private UUID userId;
    private UUID teamId;
    private UUID providerId;
    private UUID chatId;
    private ChatEntity chat;
    private Team team;

    @BeforeEach
    void setUp() {
        planningAgentService = mock(PlanningAgentService.class);
        chatService = mock(ChatService.class);
        sessionRegistry = mock(ClientSessionRegistry.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        chatFluxRegistry = new ChatFluxRegistry();
        ChatRepository chatRepository = mock(ChatRepository.class);
        modelProviderRepository = mock(ModelProviderRepository.class);
        chatUsageSessionRepository = mock(ChatUsageSessionRepository.class);

        handler = new ClientChatContinueRpcHandler(
                planningAgentService,
                chatService,
                sessionRegistry,
                teamMemberRepository,
                chatFluxRegistry,
                chatRepository,
                modelProviderRepository,
                chatUsageSessionRepository);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        providerId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        team = new Team("Engineering", "Engineering team");
        team.setId(teamId);
        chat = mock(ChatEntity.class);
        when(chat.getTeam()).thenReturn(team);

        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
    }

    @Test
    void handle_successfulResume_registersFlux() {
        String directive = "Continue planning remaining steps";
        when(chatService.readAndResolveInterstitialDirective(chatId)).thenReturn(Optional.of(directive));
        when(planningAgentService.continuePlanning(teamId, providerId, "gpt-4o", chatId, directive))
                .thenReturn(Flux.empty());

        ClientRpcPayload.ChatContinue params = new ClientRpcPayload.ChatContinue(
                chatId.toString(), teamId.toString(), providerId.toString(), "gpt-4o");

        StepVerifier.create(handler.handle("ws-1", 1, params)).verifyComplete();
        verify(chatService).verifyChat(chatId, teamId);
        assertThat(chatFluxRegistry.containsKey(chatId)).isTrue();
    }

    @Test
    void handle_fallsBackToLookup_whenParamsOmitted() {
        String directive = "Resume directive";
        when(chatService.readAndResolveInterstitialDirective(chatId)).thenReturn(Optional.of(directive));

        ModelProvider provider = mock(ModelProvider.class);
        when(provider.getId()).thenReturn(providerId);
        when(modelProviderRepository.findFirstByTeamIdAndIsActiveTrue(teamId)).thenReturn(Optional.of(provider));

        ChatUsageSession session = mock(ChatUsageSession.class);
        when(session.getModel()).thenReturn("claude-sonnet-4");
        when(chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chatId))
                .thenReturn(List.of(session));

        when(planningAgentService.continuePlanning(teamId, providerId, "claude-sonnet-4", chatId, directive))
                .thenReturn(Flux.empty());

        ClientRpcPayload.ChatContinue params = new ClientRpcPayload.ChatContinue(chatId.toString());

        StepVerifier.create(handler.handle("ws-1", 1, params)).verifyComplete();
        assertThat(chatFluxRegistry.containsKey(chatId)).isTrue();
    }

    @Test
    void handle_notAuthenticated_throwsNotAuthenticatedError() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        ClientRpcPayload.ChatContinue params = new ClientRpcPayload.ChatContinue(chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_notTeamMember_throwsNotAuthorisedError() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        ClientRpcPayload.ChatContinue params = new ClientRpcPayload.ChatContinue(
                chatId.toString(), teamId.toString(), providerId.toString(), "gpt-4o");

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHORIZED));
    }

    @Test
    void handle_providerNotFound_throwsProviderNotFoundError() {
        when(chatService.readAndResolveInterstitialDirective(chatId)).thenReturn(Optional.empty());
        when(planningAgentService.continuePlanning(
                        teamId, providerId, "gpt-4o", chatId, PlanningAgentService.DEFAULT_CONTINUATION_DIRECTIVE))
                .thenThrow(new PlanningAgentService.LlmProviderNotFoundException(providerId));

        ClientRpcPayload.ChatContinue params = new ClientRpcPayload.ChatContinue(
                chatId.toString(), teamId.toString(), providerId.toString(), "gpt-4o");

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.LLM_PROVIDER_NOT_FOUND));
    }

    @Test
    void handle_streamError_emitsRpcErrorWithChatId() {
        RuntimeException error = new RuntimeException("stream error");
        when(chatService.readAndResolveInterstitialDirective(chatId)).thenReturn(Optional.empty());
        when(planningAgentService.continuePlanning(
                        teamId, providerId, "gpt-4o", chatId, PlanningAgentService.DEFAULT_CONTINUATION_DIRECTIVE))
                .thenReturn(Flux.error(error));

        ClientRpcPayload.ChatContinue params = new ClientRpcPayload.ChatContinue(
                chatId.toString(), teamId.toString(), providerId.toString(), "gpt-4o");

        StepVerifier.create(handler.handle("ws-1", 1, params))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(RpcErrorException.class);
                    RpcErrorException rpc = (RpcErrorException) ex;
                    assertThat(rpc.error().code()).isEqualTo(JsonRpcErrorCodes.INTERNAL_ERROR);
                    assertThat(rpc.error().data().toString()).contains(chatId.toString());
                })
                .verify();
    }

    @Test
    void handle_methodNameAndPayloadType_areCorrect() {
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ChatContinue.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ChatContinue.class);
    }
}
