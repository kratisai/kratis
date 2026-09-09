package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SandboxExecutionScenarioFactory;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.ResolveHitlRequest;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;

@SpringIntegrationTest
class ActivityAndPermissionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionScenarioFactory scenarioFactory;

    @Autowired
    private SandboxPermissionRuleRepository permissionRuleRepository;

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

        SandboxPermissionRule echoRule = new SandboxPermissionRule();
        echoRule.setTeam(auth.team());
        echoRule.setCommandRoot("echo*");
        echoRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        permissionRuleRepository.save(echoRule);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testActivityStreamingAndPermissionWorkflow() throws Exception {
        var scenario = scenarioFactory.startOnConnector(auth, chat, "Test Connector", "test-plan", "activity-repo");
        UUID executionId = scenario.executionId();

        CountDownLatch activityLatch = new CountDownLatch(1);
        CountDownLatch permissionLatch = new CountDownLatch(1);

        try (WsPair pair = WsPair.connect(
                port,
                auth,
                scenario,
                client -> client.whenContains("execution_activity", (wsSession, payload) -> activityLatch.countDown())
                        .whenContains("execution_hitl_required", (wsSession, payload) -> permissionLatch.countDown()),
                sidecar -> sidecar.whenMethod("env.acp_prompt", (wsSession, payload) -> {
                            JsonRpcInboundRequest activityRequest = new JsonRpcInboundRequest(
                                    EnvironmentRpcPayload.Activity.METHOD,
                                    objectMapper.valueToTree(Map.of(
                                            "activityType", "THINKING",
                                            "description", "Thinking...",
                                            "status", "in_progress",
                                            "executionId", executionId.toString())),
                                    null);
                            wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(activityRequest)));

                            JsonRpcInboundRequest echoPermRequest = new JsonRpcInboundRequest(
                                    EnvironmentRpcPayload.HitlRequest.METHOD,
                                    objectMapper.valueToTree(Map.of(
                                            "hitlId", "tool-call-99",
                                            "message", "Approve echo hello",
                                            "kind", "approval",
                                            "executionId", executionId.toString(),
                                            "command", "echo hello",
                                            "options", SidecarWebSocketFixture.defaultPermissionOptions())),
                                    99);
                            wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(echoPermRequest)));

                            JsonRpcInboundRequest permRequest = new JsonRpcInboundRequest(
                                    EnvironmentRpcPayload.HitlRequest.METHOD,
                                    objectMapper.valueToTree(Map.of(
                                            "hitlId", "tool-call-100",
                                            "message", "Approve rm -rf /",
                                            "kind", "approval",
                                            "executionId", executionId.toString(),
                                            "command", "rm -rf /",
                                            "options", SidecarWebSocketFixture.defaultPermissionOptions())),
                                    100);
                            wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(permRequest)));
                        })
                        .expectTrigger("\"id\":99", 1)
                        .expectTrigger("\"id\":100", 1))) {
            assertThat(activityLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_activity"))
                    .isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("THINKING")).isTrue();

            assertThat(pair.sidecar().awaitTrigger("\"id\":99", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().hasReceivedMessageContaining("\"id\":99", "\"optionId\":\"allow-always\""))
                    .isTrue();

            assertThat(permissionLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pair.client().hasReceivedMessageContaining("execution_hitl_required"))
                    .isTrue();

            Thread.sleep(500);
            assertThat(pair.sidecar().hasReceivedMessageContaining("\"id\":100", "\"optionId\""))
                    .as("HITL command should NOT receive an immediate response")
                    .isFalse();

            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                    .apply(springSecurity())
                    .build();
            ResolveHitlRequest approveRequest =
                    new ResolveHitlRequest(executionId, "tool-call-100", HitlResponse.APPROVED, "allow", null);
            mockMvc.perform(post("/api/v1/hitl/resolve")
                            .header("Authorization", "Bearer " + auth.accessToken())
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(approveRequest)))
                    .andExpect(status().isNoContent());

            assertThat(pair.sidecar().awaitTrigger("\"id\":100", 5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(pair.sidecar().hasReceivedMessageContaining("\"id\":100", "\"optionId\":\"allow\""))
                    .isTrue();
        }
    }
}
