package com.kratisai.controlplane.websocket;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.CheckoutStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.OutputStream;
import com.kratisai.controlplane.api.wsdto.StopReason;
import java.util.List;
import java.util.Map;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Request-style methods are answered with their protocol result envelopes. */
public class SidecarWebSocketFixture extends WebSocketFixture<SidecarWebSocketFixture> {

    private volatile String currentExecutionId;

    /** Default permission options mirroring the sidecar's synthesized set when the agent sends none. */
    public static List<Map<String, String>> defaultPermissionOptions() {
        return List.of(
                Map.of("optionId", "allow", "name", "Allow", "kind", "allow_once"),
                Map.of("optionId", "allow-always", "name", "Always allow", "kind", "allow_always"),
                Map.of("optionId", "reject", "name", "Reject", "kind", "reject_once"));
    }

    public SidecarWebSocketFixture(String token) {
        super(token);

        // Register default standard sidecar handlers
        rules.add(new Rule(payload -> payload.contains("\"method\":\"env.checkout\""), (session, payload) -> {
            updateExecutionId(payload);
            JsonRpcInboundRequest checkoutComplete = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.CheckoutComplete.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "status",
                            CheckoutStatus.SUCCESS.getValue(),
                            "commitHash",
                            "abcdef1234567890",
                            "executionId",
                            getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(checkoutComplete)));
        }));

        rules.add(new Rule(
                payload -> payload.contains("\"method\":\"env.exec\""),
                (session, payload) -> sendResult(
                        session, payload, Map.of("status", ExecStatus.COMPLETED.getValue(), "exitCode", 0))));

        rules.add(new Rule(
                payload -> payload.contains("\"method\":\"env.registerGitAuth\""),
                (session, payload) -> sendResult(session, payload, Map.of("status", "success"))));

        rules.add(new Rule(payload -> payload.contains("\"method\":\"env.launch_acp_agent\""), (session, payload) -> {
            updateExecutionId(payload);
            sendResult(
                    session,
                    payload,
                    Map.of(
                            "status",
                            "launched",
                            "sessionId",
                            "test-session-123",
                            "agentName",
                            "test-agent",
                            "agentVersion",
                            "1.0.0"));
            JsonRpcInboundRequest initialized = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.AcpInitialized.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "sessionId",
                            "test-session-123",
                            "agentName",
                            "test-agent",
                            "agentVersion",
                            "1.0.0",
                            "executionId",
                            getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(initialized)));
        }));

        rules.add(new Rule(payload -> payload.contains("\"method\":\"env.acp_prompt\""), (session, payload) -> {
            updateExecutionId(payload);
            sendAcpPromptResult(session, payload);
            sendAcpPromptNotifications(session);
        }));

        rules.add(new Rule(payload -> payload.contains("\"method\":\"env.acp_cancel\""), (session, payload) -> {
            JsonRpcInboundRequest completeLine = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.Complete.METHOD,
                    objectMapper.valueToTree(Map.of("exitCode", 0, "executionId", getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(completeLine)));
        }));

        rules.add(new Rule(payload -> payload.contains("\"method\":\"env.terminate\""), (session, payload) -> {
            sendResult(session, payload, Map.of("status", "terminated", "exitCode", 0));
            JsonRpcInboundRequest completeLine = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.Complete.METHOD,
                    objectMapper.valueToTree(Map.of("exitCode", 0, "executionId", getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(completeLine)));
        }));
    }

    private void updateExecutionId(String payload) {
        try {
            var tree = objectMapper.readTree(payload);
            var params = tree.get("params");
            if (params != null && params.has("executionId")) {
                currentExecutionId = params.get("executionId").asText();
                return;
            }
            var result = tree.get("result");
            if (result != null && result.has("executionId")) {
                currentExecutionId = result.get("executionId").asText();
            }
        } catch (Exception ignored) {
        }
    }

    private String getExecutionId() {
        return currentExecutionId != null ? currentExecutionId : "123e4567-e89b-12d3-a456-426614174000";
    }

    /**
     * Configure this sidecar to simulate an ACP agent that requests permission for a specific
     * command during the prompt phase.
     */
    public SidecarWebSocketFixture withAcpCommand(String command) {
        return whenMethod("env.acp_prompt", (session, payload) -> {
            updateExecutionId(payload);
            sendAcpPromptResult(session, payload);
            JsonRpcInboundRequest hitlRequest = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.HitlRequest.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "hitlId",
                            "tool-call-100",
                            "message",
                            "Approve " + command,
                            "kind",
                            "approval",
                            "executionId",
                            getExecutionId(),
                            "command",
                            command,
                            "options",
                            defaultPermissionOptions())),
                    100);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(hitlRequest)));
        });
    }

    /** Configure this sidecar to stream an ACP tool failure to the UI during the prompt phase. */
    public SidecarWebSocketFixture withAcpErrorOutput(String error) {
        return whenMethod("env.acp_prompt", (session, payload) -> {
            updateExecutionId(payload);
            sendAcpPromptResult(session, payload);
            JsonRpcInboundRequest standardOutput = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.Output.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "line",
                            "Hello World From Sidecar",
                            "stream",
                            OutputStream.STDOUT.getValue(),
                            "executionId",
                            getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(standardOutput)));

            JsonRpcInboundRequest outputLine = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.Output.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "line", error, "stream", OutputStream.STDERR.getValue(), "executionId", getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(outputLine)));

            JsonRpcInboundRequest promptComplete = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.AcpPromptComplete.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "sessionId",
                            "test-session-123",
                            "stopReason",
                            StopReason.END_TURN.getValue(),
                            "executionId",
                            getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(promptComplete)));
        });
    }

    /** Configure activity emission during prompt (for activity stream tests). */
    public SidecarWebSocketFixture withActivity(ActivityType type, String description) {
        return withActivity(type, description, ActivityStatus.COMPLETED);
    }

    /** Configure activity emission with an explicit lifecycle status. */
    public SidecarWebSocketFixture withActivity(ActivityType type, String description, ActivityStatus status) {
        return whenMethod("env.acp_prompt", (session, payload) -> {
            updateExecutionId(payload);
            sendAcpPromptResult(session, payload);
            JsonRpcInboundRequest activity = new JsonRpcInboundRequest(
                    EnvironmentRpcPayload.Activity.METHOD,
                    objectMapper.valueToTree(Map.of(
                            "activityType",
                            type.getValue(),
                            "description",
                            description,
                            "status",
                            status.getValue(),
                            "executionId",
                            getExecutionId())),
                    null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(activity)));
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        JsonRpcInboundRequest registerRequest = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Register.METHOD, objectMapper.valueToTree(Map.of("token", token)), 1);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(registerRequest)));
    }

    private void sendAcpPromptResult(WebSocketSession session, String payload) throws Exception {
        sendResult(session, payload, Map.of("status", "completed", "stopReason", StopReason.END_TURN.getValue()));
    }

    private void sendAcpPromptNotifications(WebSocketSession session) throws Exception {
        JsonRpcInboundRequest outputLine = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.Output.METHOD,
                objectMapper.valueToTree(Map.of(
                        "line",
                        "Hello World From Sidecar",
                        "stream",
                        OutputStream.STDOUT.getValue(),
                        "executionId",
                        getExecutionId())),
                null);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(outputLine)));

        JsonRpcInboundRequest promptComplete = new JsonRpcInboundRequest(
                EnvironmentRpcPayload.AcpPromptComplete.METHOD,
                objectMapper.valueToTree(Map.of(
                        "sessionId",
                        "test-session-123",
                        "stopReason",
                        StopReason.END_TURN.getValue(),
                        "executionId",
                        getExecutionId())),
                null);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(promptComplete)));
    }

    private void sendResult(WebSocketSession session, String payload, Object result) throws Exception {
        JsonRpcInboundRequest request = objectMapper.readValue(payload, JsonRpcInboundRequest.class);
        Object id = request.id() != null ? request.id() : 0;
        session.sendMessage(
                new TextMessage(objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result", result))));
    }
}
