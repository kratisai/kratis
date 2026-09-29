package com.kratisai.controlplane.websocket.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.planningagent.PlanningAgentService;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import com.kratisai.controlplane.service.ClientSessionRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ClientChatSendRpcHandlerTest {

    private PlanningAgentService planningAgentService;
    private ChatService chatService;
    private ClientSessionRegistry sessionRegistry;
    private TeamMemberRepository teamMemberRepository;
    private ChatFluxRegistry chatFluxRegistry;
    private ClientChatSendRpcHandler handler;
    private UUID userId;
    private UUID teamId;
    private UUID providerId;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        planningAgentService = mock(PlanningAgentService.class);
        chatService = mock(ChatService.class);
        sessionRegistry = mock(ClientSessionRegistry.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        chatFluxRegistry = new ChatFluxRegistry();
        handler = new ClientChatSendRpcHandler(
                planningAgentService, chatService, sessionRegistry, teamMemberRepository, chatFluxRegistry);

        userId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        providerId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.of(userId.toString()));
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(true);
    }

    @Test
    void handle_successfulStream_registersFlux() {
        when(planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "hello"))
                .thenReturn(Flux.empty());

        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "hello", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

        StepVerifier.create(handler.handle("ws-1", 1, params)).verifyComplete();
        verify(chatService).verifyChat(chatId, teamId);
        assertThat(chatFluxRegistry.containsKey(chatId)).isTrue();
    }

    @Test
    void handle_notAuthenticated_throwsNotAuthenticatedError() {
        when(sessionRegistry.getUserId("ws-1")).thenReturn(Optional.empty());

        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "hello", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHENTICATED));
    }

    @Test
    void handle_blankMessage_throwsInvalidParamsError() {
        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> {
                    JsonRpcError error = ((RpcErrorException) ex).error();
                    assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
                    assertThat(error.data().toString()).contains("Message is required");
                });
    }

    @Test
    void handle_blankTeamId_throwsInvalidParamsError() {
        ClientRpcPayload.ChatSend params =
                new ClientRpcPayload.ChatSend("hello", "", providerId.toString(), "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> {
                    JsonRpcError error = ((RpcErrorException) ex).error();
                    assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
                    assertThat(error.data().toString()).contains("teamId is required");
                });
    }

    @Test
    void handle_blankProviderId_throwsInvalidParamsError() {
        ClientRpcPayload.ChatSend params =
                new ClientRpcPayload.ChatSend("hello", teamId.toString(), "", "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> {
                    JsonRpcError error = ((RpcErrorException) ex).error();
                    assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
                    assertThat(error.data().toString()).contains("providerId is required");
                });
    }

    @Test
    void handle_blankModelName_throwsInvalidParamsError() {
        ClientRpcPayload.ChatSend params =
                new ClientRpcPayload.ChatSend("hello", teamId.toString(), providerId.toString(), "", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> {
                    JsonRpcError error = ((RpcErrorException) ex).error();
                    assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
                    assertThat(error.data().toString()).contains("modelName is required");
                });
    }

    @Test
    void handle_blankChatId_throwsInvalidParamsError() {
        ClientRpcPayload.ChatSend params =
                new ClientRpcPayload.ChatSend("hello", teamId.toString(), providerId.toString(), "gpt-4o", "");

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> {
                    JsonRpcError error = ((RpcErrorException) ex).error();
                    assertThat(error.code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
                    assertThat(error.data().toString()).contains("chatId is required");
                });
    }

    @Test
    void handle_notTeamMember_throwsNotAuthorisedError() {
        when(teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)).thenReturn(false);

        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "hello", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.NOT_AUTHORIZED));
    }

    @Test
    void handle_providerNotFound_throwsProviderNotFoundError() {
        when(planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "hello"))
                .thenThrow(new PlanningAgentService.LlmProviderNotFoundException(providerId));

        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "hello", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.LLM_PROVIDER_NOT_FOUND));
    }

    @Test
    void handle_providerInactive_throwsProviderInactiveError() {
        when(planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "hello"))
                .thenThrow(new PlanningAgentService.LlmProviderInactiveException(providerId));

        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "hello", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

        assertThatThrownBy(() -> handler.handle("ws-1", 1, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(ex -> assertThat(((RpcErrorException) ex).error().code())
                        .isEqualTo(JsonRpcErrorCodes.LLM_PROVIDER_INACTIVE));
    }

    @Test
    void handle_streamError_emitsRpcErrorWithChatId() {
        RuntimeException error = new RuntimeException("stream failed");
        when(planningAgentService.streamMessage(teamId, providerId, "gpt-4o", chatId, "hello"))
                .thenReturn(Flux.error(error));

        ClientRpcPayload.ChatSend params = new ClientRpcPayload.ChatSend(
                "hello", teamId.toString(), providerId.toString(), "gpt-4o", chatId.toString());

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
        assertThat(handler.getMethodName()).isEqualTo(ClientRpcPayload.ChatSend.METHOD);
        assertThat(handler.getPayloadType()).isEqualTo(ClientRpcPayload.ChatSend.class);
    }
}
