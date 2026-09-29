package com.kratisai.controlplane.websocket;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * A matcher-based test fixture for simulating user UI client WebSocket behavior. Provides reusable
 * HITL (Human-In-The-Loop) permission handlers for E2E tests.
 */
public class ClientWebSocketFixture extends WebSocketFixture<ClientWebSocketFixture> {

    private static final Logger logger = LoggerFactory.getLogger(ClientWebSocketFixture.class);

    /** Callback resolving a HITL approval request with the offered options. */
    @FunctionalInterface
    public interface PermissionApprover {
        void approve(UUID executionId, String hitlId, String command, List<PermissionOption> options);
    }

    private final CountDownLatch subscribedLatch = new CountDownLatch(1);

    public ClientWebSocketFixture(String token, UUID teamId) {
        super(token);
        if (teamId != null) {
            whenContains("authenticated", (session, payload) -> {
                JsonRpcInboundRequest subRequest = new JsonRpcInboundRequest(
                        ClientRpcPayload.Subscribe.METHOD,
                        objectMapper.valueToTree(Map.of("teamId", teamId.toString())),
                        2);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subRequest)));
            });
            whenType(ClientPayload.SubscriptionResult.class, (session, msg) -> {
                subscribedLatch.countDown();
            });
        } else {
            subscribedLatch.countDown();
        }
    }

    public boolean awaitSubscription(long timeout, TimeUnit unit) throws InterruptedException {
        return subscribedLatch.await(timeout, unit);
    }

    /**
     * Registers a handler that automatically approves all permission requests received via
     * WebSocket. When an {@code execution_hitl_required} notification is received, the
     * provided approver callback is invoked with the execution ID, command, and the offered
     * options (so the callback can resolve with a real optionId).
     *
     * <p>This is useful for E2E tests where the agent execution should proceed without manual HITL
     * intervention.
     *
     * @param approver a callback that performs the actual approval (e.g., via REST API call).
     * @return this fixture for method chaining
     */
    public ClientWebSocketFixture autoApprovePermissions(PermissionApprover approver) {
        whenType(
                ClientPayload.ExecutionHitlRequiredResult.class,
                msg -> msg.kind() == HitlKind.APPROVAL,
                (session, msg) -> {
                    logger.info(
                            "[auto-approve] Auto-approving permission for execution={}, command='{}'",
                            msg.executionId(),
                            msg.command());
                    approver.approve(msg.executionId(), msg.hitlId(), msg.command(), msg.options());
                });
        return this;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        JsonRpcInboundRequest authRequest = new JsonRpcInboundRequest(
                ClientRpcPayload.Auth.METHOD, objectMapper.valueToTree(Map.of("token", token)), 1);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(authRequest)));
    }
}
