package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcRequest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class EnvironmentRpcClientTest {

    private static final UUID ENVIRONMENT_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentRpcClient client;

    @Mock
    private WebSocketSession session;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @BeforeEach
    void setUp() {
        WebSocketDispatch dispatch = new WebSocketDispatch(objectMapper, null, null, Runnable::run);
        client = new EnvironmentRpcClient(objectMapper, dispatch, sessionRegistry);
    }

    private void stubConnectedSession() {
        lenient().when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        when(sessionRegistry.getSessionForEnvironment(ENVIRONMENT_ID)).thenReturn(session);
    }

    @Test
    void send_restrictedToOutboundNotifications() throws Exception {
        stubConnectedSession();
        client.send(
                ENVIRONMENT_ID,
                new EnvironmentRpcPayload.Checkout("https://example.com/repo.git", "main", null, "exec-1"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("env.checkout");
        assertThat(captor.getValue().getPayload()).contains("\"jsonrpc\":\"2.0\"");
        assertThat(captor.getValue().getPayload()).doesNotContain("\"id\"");
    }

    @Test
    void request_awaitsMatchingResponse() throws Exception {
        stubConnectedSession();
        doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    JsonNode envelope = objectMapper.readTree(msg.getPayload());
                    String id = envelope.get("id").asText();
                    ObjectNode response = objectMapper.createObjectNode();
                    response.put("jsonrpc", "2.0");
                    response.put("id", id);
                    ObjectNode result = response.putObject("result");
                    result.put("status", "completed");
                    result.put("exitCode", 0);
                    CompletableFuture.runAsync(() -> client.completeResponse(response));
                    return null;
                })
                .when(session)
                .sendMessage(any(TextMessage.class));

        EnvironmentConnectorResult.Exec result =
                client.request(ENVIRONMENT_ID, new EnvironmentRpcPayload.Exec("echo hi", true, "exec-1"));

        assertThat(result.status()).isEqualTo(ExecStatus.COMPLETED);
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void completeResponse_withError_failsWaiter() throws Exception {
        stubConnectedSession();
        doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    JsonNode envelope = objectMapper.readTree(msg.getPayload());
                    String id = envelope.get("id").asText();
                    ObjectNode response = objectMapper.createObjectNode();
                    response.put("jsonrpc", "2.0");
                    response.put("id", id);
                    ObjectNode error = response.putObject("error");
                    error.put("code", -32000);
                    error.put("message", "boom");
                    CompletableFuture.runAsync(() -> client.completeResponse(response));
                    return null;
                })
                .when(session)
                .sendMessage(any(TextMessage.class));

        assertThatThrownBy(
                        () -> client.request(ENVIRONMENT_ID, new EnvironmentRpcPayload.Exec("false", true, "exec-1")))
                .isInstanceOf(EnvironmentRpcClient.EnvironmentRpcException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void completeResponse_withErrorData_surfacesDataOnException() throws Exception {
        stubConnectedSession();
        doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    JsonNode envelope = objectMapper.readTree(msg.getPayload());
                    String id = envelope.get("id").asText();
                    ObjectNode response = objectMapper.createObjectNode();
                    response.put("jsonrpc", "2.0");
                    response.put("id", id);
                    ObjectNode error = response.putObject("error");
                    error.put("code", -32000);
                    error.put("message", "Failed to push branch to remote");
                    error.put("data", "! [rejected] branch -> branch (non-fast-forward)");
                    CompletableFuture.runAsync(() -> client.completeResponse(response));
                    return null;
                })
                .when(session)
                .sendMessage(any(TextMessage.class));

        assertThatThrownBy(
                        () -> client.request(ENVIRONMENT_ID, new EnvironmentRpcPayload.Exec("false", true, "exec-1")))
                .isInstanceOfSatisfying(EnvironmentRpcClient.EnvironmentRpcException.class, ex -> {
                    assertThat(ex.getMessage()).contains("Failed to push branch to remote");
                    assertThat(ex.getData()).contains("non-fast-forward");
                });
    }

    @Test
    void request_unknownEnvironment_throws() {
        when(sessionRegistry.getSessionForEnvironment(ENVIRONMENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> client.request(ENVIRONMENT_ID, new EnvironmentRpcPayload.Terminate()))
                .isInstanceOf(EnvironmentRpcClient.EnvironmentRpcException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    void failAll_onlyFailsMatchingSession() throws Exception {
        stubConnectedSession();
        WebSocketSession otherSession = org.mockito.Mockito.mock(WebSocketSession.class);
        when(otherSession.getId()).thenReturn("ws-other");
        when(otherSession.isOpen()).thenReturn(true);
        UUID otherEnv = UUID.fromString("33333333-4444-5555-6666-777777777777");
        when(sessionRegistry.getSessionForEnvironment(otherEnv)).thenReturn(otherSession);

        java.util.concurrent.CountDownLatch sentFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch sentSecond = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> secondId =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
                    sentFirst.countDown();
                    return null;
                })
                .when(session)
                .sendMessage(any(TextMessage.class));
        doAnswer(inv -> {
                    TextMessage msg = inv.getArgument(0);
                    secondId.set(
                            objectMapper.readTree(msg.getPayload()).get("id").asText());
                    sentSecond.countDown();
                    return null;
                })
                .when(otherSession)
                .sendMessage(any(TextMessage.class));

        CompletableFuture<EnvironmentConnectorResult.Terminate> first = CompletableFuture.supplyAsync(() -> {
            try {
                return client.request(ENVIRONMENT_ID, new EnvironmentRpcPayload.Terminate(), 5, TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(sentFirst.await(2, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<EnvironmentConnectorResult.Terminate> second = CompletableFuture.supplyAsync(() -> {
            try {
                return client.request(otherEnv, new EnvironmentRpcPayload.Terminate(), 5, TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(sentSecond.await(2, TimeUnit.SECONDS)).isTrue();

        client.failAll("ws-1", "session closed");

        assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(EnvironmentRpcClient.EnvironmentRpcException.class);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", secondId.get());
        ObjectNode result = response.putObject("result");
        result.put("status", "terminated");
        result.put("exitCode", 0);
        client.completeResponse(response);

        assertThat(second.get(2, TimeUnit.SECONDS).status()).isNotNull();
    }

    @Test
    void replyError_writesErrorEnvelope() throws Exception {
        stubConnectedSession();
        client.replyError(ENVIRONMENT_ID, "req-1", new JsonRpcError(-32000, "cancelled", null));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"error\"").contains("cancelled");
    }

    @Test
    void request_derivesWireMethodFromPayload() {
        EnvironmentRpcPayload.Exec payload = new EnvironmentRpcPayload.Exec("ls", true, "exec-1");
        JsonRpcRequest<EnvironmentRpcPayload.Exec> request = new JsonRpcRequest<>(payload, "id-1");
        assertThat(request.method()).isEqualTo("env.exec");
        assertThat(request.id()).isEqualTo("id-1");
        assertThat(request.params()).isEqualTo(payload);
    }

    @Test
    void request_noParamsPayload_omitsParamsFromWire() {
        JsonRpcRequest<EnvironmentRpcPayload.Terminate> request =
                new JsonRpcRequest<>(new EnvironmentRpcPayload.Terminate(), 7);
        assertThat(request.method()).isEqualTo("env.terminate");
        assertThat(request.params()).isNull();
        assertThat(objectMapper.valueToTree(request).path("params").isMissingNode())
                .isTrue();
    }

    @Test
    void resultType_coversEveryOutboundRequest() {
        assertThat(new EnvironmentRpcPayload.Exec("x", false, "exec-1").resultType())
                .isEqualTo(EnvironmentConnectorResult.Exec.class);
        assertThat(new EnvironmentRpcPayload.LaunchAcpAgent("cmd", null, "exec-1").resultType())
                .isEqualTo(EnvironmentConnectorResult.LaunchAcpAgent.class);
        assertThat(new EnvironmentRpcPayload.AcpPrompt("do it", "exec-1").resultType())
                .isEqualTo(EnvironmentConnectorResult.AcpPrompt.class);
        assertThat(new EnvironmentRpcPayload.Terminate().resultType())
                .isEqualTo(EnvironmentConnectorResult.Terminate.class);
        assertThat(new EnvironmentRpcPayload.RegisterGitAuth("PAT", "", "Kratis", "kratis@example.com").resultType())
                .isEqualTo(EnvironmentConnectorResult.RegisterGitAuth.class);
        assertThat(new EnvironmentRpcPayload.GitDiffSummary("main", "exec-1").resultType())
                .isEqualTo(EnvironmentConnectorResult.GitDiffSummary.class);
        assertThat(new EnvironmentRpcPayload.GitFileDiff("src/A.java", "main", "exec-1").resultType())
                .isEqualTo(EnvironmentConnectorResult.GitFileDiff.class);
        assertThat(new EnvironmentRpcPayload.ReadFileSlice("src/A.java", 1, 10, "exec-1").resultType())
                .isEqualTo(EnvironmentConnectorResult.ReadFileSlice.class);
    }
}
