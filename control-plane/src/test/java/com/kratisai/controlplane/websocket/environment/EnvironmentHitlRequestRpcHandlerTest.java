package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.HitlResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class EnvironmentHitlRequestRpcHandlerTest {

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxPermissionRuleRepository ruleRepository;

    @Mock
    private ExecutionEnvironmentRepository environmentRepository;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private PendingHitlRegistry pendingHitlRegistry;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnvironmentHitlRequestRpcHandler handler;

    private final UUID envId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();
    private final String sessionId = "ws-session-1";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        handler = new EnvironmentHitlRequestRpcHandler(
                sessionRegistry,
                ruleRepository,
                environmentRepository,
                executionRepository,
                executionGuard,
                pendingHitlRegistry,
                eventPublisher);
    }

    private ExecutionEnvironment createEnvironment() {
        Team team = new Team();
        team.setId(teamId);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        env.setTeam(team);
        return env;
    }

    private SandboxExecution createExecutionInEnvironment(UUID environmentId) {
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(environmentId);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(UUID.randomUUID());
        execution.setEnvironment(env);
        return execution;
    }

    private void stubSessionAndEnvironment() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(createEnvironment()));
    }

    private EnvironmentRpcPayload.HitlRequest approvalParams(String command, String hitlId) {
        return new EnvironmentRpcPayload.HitlRequest(
                hitlId,
                "Approve " + command,
                HitlKind.APPROVAL,
                UUID.randomUUID().toString(),
                command,
                "Run " + command,
                "execute",
                defaultOptions(),
                null,
                null);
    }

    private List<PermissionOption> defaultOptions() {
        return List.of(
                new PermissionOption("allow", "Allow", ApprovalOptionKind.ALLOW_ONCE),
                new PermissionOption("allow-always", "Always allow", ApprovalOptionKind.ALLOW_ALWAYS),
                new PermissionOption("reject", "Reject", ApprovalOptionKind.REJECT_ONCE));
    }

    @Test
    void handle_withNullParams_throwsNullPointerException() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.of(envId));
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(createEnvironment()));

        assertThatThrownBy(() -> handler.handle(sessionId, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void handle_withUnmappedSession_returnsRegistrationError() {
        when(sessionRegistry.getEnvironmentId(sessionId)).thenReturn(Optional.empty());

        EnvironmentRpcPayload.HitlRequest params = approvalParams("ls", "tool-call-1");
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32001));
    }

    @Test
    void handle_approvalWithMatchingRule_autoApproves() {
        stubSessionAndEnvironment();

        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setCommandRoot("echo");
        rule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of(rule));

        EnvironmentRpcPayload.HitlRequest params = approvalParams("echo hello", "tool-call-1");
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isInstanceOf(HitlResult.class);
        assertThat(((HitlResult) response).response()).isEqualTo(HitlResponse.APPROVED);
        verify(pendingHitlRegistry, never()).register(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handle_approvalWithMatchingDenyRule_declinesImmediately() {
        stubSessionAndEnvironment();

        SandboxPermissionRule denyRule = new SandboxPermissionRule();
        denyRule.setCommandRoot("rm -rf");
        denyRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        denyRule.setAction(SandboxPermissionAction.DENY);
        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of(denyRule));

        EnvironmentRpcPayload.HitlRequest params = approvalParams("rm -rf /tmp/test", "tool-call-1");
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isInstanceOf(HitlResult.class);
        assertThat(((HitlResult) response).response()).isEqualTo(HitlResponse.DECLINED);
        verify(pendingHitlRegistry, never()).register(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handle_approvalWithBothAllowAndDenyMatching_denyTakesPrecedence() {
        stubSessionAndEnvironment();

        SandboxPermissionRule allowRule = new SandboxPermissionRule();
        allowRule.setCommandRoot("git");
        allowRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        allowRule.setAction(SandboxPermissionAction.ALLOW);

        SandboxPermissionRule denyRule = new SandboxPermissionRule();
        denyRule.setCommandRoot("git push --force");
        denyRule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        denyRule.setAction(SandboxPermissionAction.DENY);

        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of(allowRule, denyRule));

        EnvironmentRpcPayload.HitlRequest params = approvalParams("git push --force origin main", "tool-call-1");
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isInstanceOf(HitlResult.class);
        assertThat(((HitlResult) response).response()).isEqualTo(HitlResponse.DECLINED);
        verify(pendingHitlRegistry, never()).register(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void handle_approvalWithRuleButNoAllowOption_cancelled() {
        stubSessionAndEnvironment();

        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setCommandRoot("rm");
        rule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of(rule));

        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "tool-call-1",
                "Remove",
                HitlKind.APPROVAL,
                UUID.randomUUID().toString(),
                "rm -rf /",
                "Remove",
                "execute",
                List.of(),
                null,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isInstanceOf(HitlResult.class);
        assertThat(((HitlResult) response).response()).isEqualTo(HitlResponse.CANCELLED);
    }

    @Test
    void handle_approvalEscalatesToHitl_registersPendingAndPublishesEvent() throws Exception {
        stubSessionAndEnvironment();
        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of());

        SandboxExecution execution = createExecutionInEnvironment(envId);
        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "tool-call-42",
                "Approve rm -rf /",
                HitlKind.APPROVAL,
                execution.getId().toString(),
                "rm -rf /",
                "Run rm -rf /",
                "execute",
                defaultOptions(),
                null,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isNull();
        verify(pendingHitlRegistry)
                .register(
                        eq(execution.getId()),
                        eq(HitlKind.APPROVAL),
                        any(String.class),
                        eq(request),
                        eq("tool-call-42"),
                        eq("Approve rm -rf /"),
                        eq("rm -rf /"),
                        eq("Run rm -rf /"),
                        eq("execute"),
                        eq(defaultOptions()),
                        eq(null),
                        eq(null),
                        any(java.time.Instant.class),
                        eq(teamId));
        ArgumentCaptor<SandboxExecutionHitlRequiredEvent> captor =
                ArgumentCaptor.forClass(SandboxExecutionHitlRequiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().command()).isEqualTo("rm -rf /");
        assertThat(captor.getValue().hitlId()).isEqualTo("tool-call-42");
        assertThat(captor.getValue().kind()).isEqualTo(HitlKind.APPROVAL);
    }

    @Test
    void handle_approvalNoActiveExecution_cancelled() {
        stubSessionAndEnvironment();
        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of());

        UUID nonexistentExecId = UUID.randomUUID();
        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "tool-call-1",
                "Approve ls",
                HitlKind.APPROVAL,
                nonexistentExecId.toString(),
                "ls",
                "Run ls",
                "execute",
                defaultOptions(),
                null,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isInstanceOf(HitlResult.class);
        assertThat(((HitlResult) response).response()).isEqualTo(HitlResponse.CANCELLED);
    }

    @Test
    void handle_approvalBlankCommand_invalidParams() {
        stubSessionAndEnvironment();

        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "tool-call-1", "q", HitlKind.APPROVAL, UUID.randomUUID().toString(), " ", null, null, null, null, null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        assertThatThrownBy(() -> handler.handle(sessionId, request, params))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32602));
    }

    @Test
    void handle_questionEscalatesToHitl_registersPendingAndPublishesEvent() throws Exception {
        stubSessionAndEnvironment();

        SandboxExecution execution = createExecutionInEnvironment(envId);
        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "el-1",
                "Choose a target",
                HitlKind.QUESTION,
                execution.getId().toString(),
                null,
                null,
                null,
                null,
                null,
                Map.of("type", "object", "properties", Map.of("target", Map.of("type", "string"))));
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isNull();
        verify(pendingHitlRegistry)
                .register(
                        eq(execution.getId()),
                        eq(HitlKind.QUESTION),
                        any(String.class),
                        eq(request),
                        eq("el-1"),
                        eq("Choose a target"),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(Map.of("type", "object", "properties", Map.of("target", Map.of("type", "string")))),
                        any(java.time.Instant.class),
                        eq(teamId));
        ArgumentCaptor<SandboxExecutionHitlRequiredEvent> captor =
                ArgumentCaptor.forClass(SandboxExecutionHitlRequiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().hitlId()).isEqualTo("el-1");
        assertThat(captor.getValue().kind()).isEqualTo(HitlKind.QUESTION);
        assertThat(captor.getValue().form())
                .isEqualTo(Map.of("type", "object", "properties", Map.of("target", Map.of("type", "string"))));
    }

    @Test
    void handle_questionNoActiveExecution_cancelled() {
        stubSessionAndEnvironment();

        UUID nonexistentExecId = UUID.randomUUID();
        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "el-1",
                "Choose a target",
                HitlKind.QUESTION,
                nonexistentExecId.toString(),
                null,
                null,
                null,
                null,
                null,
                Map.of("type", "object", "properties", Map.of("target", Map.of("type", "string"))));
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        EnvironmentResponsePayload response =
                handler.handle(sessionId, request, params).blockLast();

        assertThat(response).isInstanceOf(HitlResult.class);
        assertThat(((HitlResult) response).response()).isEqualTo(HitlResponse.CANCELLED);
    }

    @Test
    void handle_approvalForExecutionFromAnotherEnvironment_throwsNotAuthorized() {
        stubSessionAndEnvironment();
        when(ruleRepository.findByTeamId(teamId)).thenReturn(List.of());

        SandboxExecution execution = createExecutionInEnvironment(UUID.randomUUID());
        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "tool-call-1",
                "Approve ls",
                HitlKind.APPROVAL,
                execution.getId().toString(),
                "ls",
                "Run ls",
                "execute",
                defaultOptions(),
                null,
                null);
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        assertThatThrownBy(() -> handler.handle(sessionId, request, params).blockLast())
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
        verify(pendingHitlRegistry, never()).register(any(), any());
    }

    @Test
    void handle_questionForExecutionFromAnotherEnvironment_throwsNotAuthorized() {
        stubSessionAndEnvironment();

        SandboxExecution execution = createExecutionInEnvironment(UUID.randomUUID());
        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));

        EnvironmentRpcPayload.HitlRequest params = new EnvironmentRpcPayload.HitlRequest(
                "el-1",
                "Choose a target",
                HitlKind.QUESTION,
                execution.getId().toString(),
                null,
                null,
                null,
                null,
                null,
                Map.of("type", "object", "properties", Map.of("target", Map.of("type", "string"))));
        JsonRpcInboundRequest request = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.HitlRequest.METHOD, objectMapper.valueToTree(params), "req-1");

        assertThatThrownBy(() -> handler.handle(sessionId, request, params).blockLast())
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
        verify(pendingHitlRegistry, never()).register(any(), any());
    }

    @Test
    void getMethodName_returnsEnvHitlRequest() {
        assertThat(handler.getMethodName()).isEqualTo("env.hitl_request");
    }
}
