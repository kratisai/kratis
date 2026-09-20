package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SandboxExecutionScenarioFactory;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.ResolveHitlRequest;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.OutputStream;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.HitlRuleAction;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.SandboxExecutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@SpringIntegrationTest
class SandboxExecutionWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private SandboxExecutionService sandboxExecutionService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionScenarioFactory scenarioFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestDataFactory.AuthContext auth;
    private ChatEntity chat;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        auth = testDataFactory.createProvisionedContext();
        chat = testDataFactory.createChat(auth.team(), auth.user(), "WebSocket Test Session");
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testEndToEndSandboxExecutionFlow() throws Exception {
        var scenario = scenarioFactory.startOnConnector(auth, chat, "Test Connector", "test-plan-ws", "ws-repo");
        UUID executionId = scenario.executionId();

        AtomicReference<ClientPayload.ExecutionOutputResult> stderrOutput = new AtomicReference<>();
        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.whenType(
                                ClientPayload.ExecutionOutputResult.class,
                                output -> output.stream() == OutputStream.STDERR,
                                (webSocketSession, output) -> stderrOutput.set(output))
                        .expectTrigger("execution_output", 2)
                        .expectTrigger("execution_complete", 1),
                sidecar -> sidecar.withAcpErrorOutput("bwrap: No permissions to create a new namespace")
                        .expectTrigger("env.launch_acp_agent", 1)
                        .expectTrigger("env.acp_prompt", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.launch_acp_agent", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().awaitTrigger("env.acp_prompt", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().awaitTrigger("execution_output", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_output", "Hello World From Sidecar"))
                    .isTrue();
            assertThat(stderrOutput.get()).isNotNull();
            assertThat(stderrOutput.get().line()).contains("bwrap: No permissions to create a new namespace");
            assertThat(stderrOutput.get().stream()).isEqualTo(OutputStream.STDERR);

            sandboxExecutionService.terminateExecution(executionId);

            assertThat(pair.client().awaitTrigger("execution_complete", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_complete", "COMPLETED"))
                    .isTrue();
        }

        SandboxExecution finalExec =
                sandboxExecutionRepository.findById(executionId).orElseThrow();
        assertThat(finalExec.getStatus()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(finalExec.getExitCode()).isEqualTo(0);
    }

    @Test
    void testSandboxExecutionHitlPermissionFlow() throws Exception {
        var scenario =
                scenarioFactory.startOnConnector(auth, chat, "HITL Test Connector", "test-plan-hitl", "hitl-repo");
        UUID executionId = scenario.executionId();

        CountDownLatch hitlNotificationLatch = new CountDownLatch(1);
        CountDownLatch resolvedNotificationLatch = new CountDownLatch(1);

        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.whenContains(
                                "execution_hitl_required", (session, payload) -> hitlNotificationLatch.countDown())
                        .whenContains(
                                "execution_hitl_resolved", (session, payload) -> resolvedNotificationLatch.countDown()),
                sidecar -> sidecar.withAcpCommand("rm -rf /")
                        .expectTrigger("env.acp_prompt", 1)
                        .expectTrigger("\"optionId\":\"allow\"", 1)
                        .expectTrigger("\"id\":101", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.acp_prompt", 5, TimeUnit.SECONDS))
                    .isTrue();

            Thread.sleep(500);
            assertThat(pair.sidecar().hasReceivedMessageContaining("100", "\"optionId\""))
                    .as("HITL command should NOT receive an immediate response")
                    .isFalse();

            assertThat(hitlNotificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_hitl_required"))
                    .isTrue();

            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                    .apply(springSecurity())
                    .build();
            ResolveHitlRequest approveRequest = new ResolveHitlRequest(
                    executionId,
                    "tool-call-100",
                    HitlResponse.APPROVED,
                    "allow",
                    null,
                    List.of(new CreateHitlRuleRequest("rm -rf", null, HitlRuleAction.ALLOW)));
            mockMvc.perform(post("/api/v1/hitl/resolve")
                            .header("Authorization", "Bearer " + auth.accessToken())
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(approveRequest)))
                    .andExpect(status().isNoContent());

            assertThat(pair.sidecar().awaitTrigger("\"optionId\":\"allow\"", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().hasReceivedMessageContaining("100", "\"optionId\":\"allow\""))
                    .isTrue();

            assertThat(resolvedNotificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_hitl_resolved"))
                    .isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("\"response\":\"approved\""))
                    .isTrue();

            JsonRpcInboundRequest secondPermRequest = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.HitlRequest.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "hitlId", "tool-call-101",
                            "message", "Approve rm -rf /",
                            "kind", "approval",
                            "executionId", executionId.toString(),
                            "command", "rm -rf /",
                            "options", SidecarWebSocketFixture.defaultPermissionOptions())),
                    101);
            pair.sidecarSession().sendMessage(new TextMessage(objectMapper.writeValueAsString(secondPermRequest)));

            assertThat(pair.sidecar().awaitTrigger("\"id\":101", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().hasReceivedMessageContaining("\"id\":101", "\"optionId\":\"allow\""))
                    .isTrue();
        }
    }

    @Test
    void testSandboxExecutionHitlPermissionRejectFlow() throws Exception {
        var scenario = scenarioFactory.startOnConnector(
                auth, chat, "HITL Reject Test Connector", "test-plan-hitl-reject", "hitl-reject-repo");
        UUID executionId = scenario.executionId();

        CountDownLatch hitlNotificationLatch = new CountDownLatch(1);
        CountDownLatch resolvedNotificationLatch = new CountDownLatch(1);

        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.whenContains(
                                "execution_hitl_required", (session, payload) -> hitlNotificationLatch.countDown())
                        .whenContains(
                                "execution_hitl_resolved", (session, payload) -> resolvedNotificationLatch.countDown()),
                sidecar -> sidecar.withAcpCommand("rm -rf /")
                        .expectTrigger("env.acp_prompt", 1)
                        .expectTrigger("\"response\":\"cancelled\"", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.acp_prompt", 5, TimeUnit.SECONDS))
                    .isTrue();

            Thread.sleep(500);
            assertThat(pair.sidecar().hasReceivedMessageContaining("\"optionId\""))
                    .as("HITL command should NOT receive an immediate response")
                    .isFalse();

            assertThat(hitlNotificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_hitl_required"))
                    .isTrue();

            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                    .apply(springSecurity())
                    .build();
            ResolveHitlRequest rejectRequest =
                    new ResolveHitlRequest(executionId, "tool-call-100", HitlResponse.CANCELLED, null, null);
            mockMvc.perform(post("/api/v1/hitl/resolve")
                            .header("Authorization", "Bearer " + auth.accessToken())
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(rejectRequest)))
                    .andExpect(status().isNoContent());

            assertThat(pair.sidecar().awaitTrigger("\"response\":\"cancelled\"", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().hasReceivedMessageContaining("100", "\"response\":\"cancelled\""))
                    .isTrue();

            assertThat(resolvedNotificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_hitl_resolved"))
                    .isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("\"response\":\"cancelled\""))
                    .isTrue();
        }
    }

    @Test
    void testSandboxExecutionHitlPermissionCancelOnTerminateFlow() throws Exception {
        var scenario = scenarioFactory.startOnConnector(
                auth, chat, "HITL Cancel Test Connector", "test-plan-hitl-cancel", "hitl-cancel-repo");
        UUID executionId = scenario.executionId();

        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.expectTrigger("execution_hitl_required", 1)
                        .expectTrigger("execution_hitl_resolved", 1)
                        .expectTrigger("execution_complete", 1),
                sidecar -> sidecar.withAcpCommand("rm -rf /")
                        .expectTrigger("env.acp_prompt", 1)
                        .expectTrigger("HITL request cancelled: execution terminated", 1)
                        .expectTrigger("env.terminate", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.acp_prompt", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().awaitTrigger("execution_hitl_required", 5, TimeUnit.SECONDS))
                    .isTrue();

            sandboxExecutionService.terminateExecution(executionId);

            assertThat(pair.sidecar().awaitTrigger("HITL request cancelled: execution terminated", 5, TimeUnit.SECONDS))
                    .as("Sidecar should receive HITL cancellation error on termination")
                    .isTrue();
            assertThat(pair.sidecar().awaitTrigger("env.terminate", 5, TimeUnit.SECONDS))
                    .as("Sidecar should receive env.terminate after pending permission cancellation")
                    .isTrue();
            assertThat(pair.client().awaitTrigger("execution_hitl_resolved", 5, TimeUnit.SECONDS))
                    .as("UI client should receive execution_hitl_resolved so the pending approval clears")
                    .isTrue();
            assertThat(pair.client().awaitTrigger("execution_complete", 5, TimeUnit.SECONDS))
                    .as("UI client should receive execution_complete after termination")
                    .isTrue();
        }
    }

    @Test
    void testSidecarWebSocketReconnectFlow() throws Exception {
        var scenario = scenarioFactory.startOnConnector(
                auth, chat, "Reconnect Test Connector", "test-plan-reconnect", "reconnect-repo");

        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.expectTrigger("execution_complete", 1),
                sidecar -> sidecar.expectTrigger("env.launch_acp_agent", 1).expectTrigger("env.acp_prompt", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.launch_acp_agent", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().awaitTrigger("env.acp_prompt", 5, TimeUnit.SECONDS))
                    .isTrue();

            // The original session's in-flight output may be dropped once it closes: notifications
            // are only accepted from currently-registered environment sessions. The reconnect flow
            // is verified by the post-reconnect output below.
            pair.sidecarSession().close();
            Thread.sleep(500);

            SidecarWebSocketFixture reconnectFixture = new SidecarWebSocketFixture(scenario.connectorToken()) {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    JsonRpcInboundRequest reconnectRequest = new JsonRpcInboundRequest(
                            EnvironmentRpcPayload.Register.METHOD,
                            objectMapper.valueToTree(Map.of(
                                    "token",
                                    token,
                                    "isReconnect",
                                    true,
                                    "activeAcpSessionId",
                                    "test-session-123",
                                    "hasActiveAgent",
                                    true,
                                    "lastEventSequence",
                                    0,
                                    "pendingHitlIds",
                                    List.of())),
                            1);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(reconnectRequest)));
                }
            };
            reconnectFixture.expectTrigger("reconnected", 1);

            try (WebSocketSession reconnectSession = new StandardWebSocketClient()
                    .execute(reconnectFixture, WsPair.envUrl(port))
                    .get(5, TimeUnit.SECONDS)) {
                assertThat(reconnectFixture.awaitTrigger("reconnected", 5, TimeUnit.SECONDS))
                        .as("Sidecar should receive reconnected status after reconnect")
                        .isTrue();

                JsonRpcInboundRequest outputLine = new JsonRpcInboundRequest(
                        EnvironmentRpcPayload.Output.METHOD,
                        objectMapper.valueToTree(Map.of(
                                "line", "Post-reconnect output",
                                "stream", "stdout",
                                "executionId", scenario.executionId().toString())),
                        null);
                reconnectSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(outputLine)));

                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                                pair.client().hasReceivedMessageContaining("execution_output", "Post-reconnect output"))
                        .as("UI client should receive output after reconnect")
                        .isTrue());
            }
        }
    }

    @Test
    void testSandboxExecutionAcpPromptCompleteNotifiesClientOfStatusChange() throws Exception {
        var scenario = scenarioFactory.startOnConnector(
                auth, chat, "ACP Complete Connector", "test-plan-acp-complete", "acp-repo");
        UUID executionId = scenario.executionId();

        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.expectTrigger("execution_status_changed", 1).expectTrigger("execution_output", 1),
                sidecar -> sidecar.expectTrigger("env.launch_acp_agent", 1).expectTrigger("env.acp_prompt", 1))) {
            assertThat(pair.sidecar().awaitTrigger("env.launch_acp_agent", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().awaitTrigger("env.acp_prompt", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().awaitTrigger("execution_output", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().awaitTrigger("execution_status_changed", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_status_changed", executionId.toString()))
                    .isTrue();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                SandboxExecution updatedExec =
                        sandboxExecutionRepository.findById(executionId).orElseThrow();
                assertThat(updatedExec.getStatus()).isEqualTo(SandboxExecutionStatus.IDLE);
            });
        }
    }
}
