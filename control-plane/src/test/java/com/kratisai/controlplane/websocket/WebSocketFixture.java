package com.kratisai.controlplane.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientPayloadType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Typed rules deserialize against the closed {@link ClientPayload} set keyed by the
 * {@code result.type} discriminator.
 */
public abstract class WebSocketFixture<T extends WebSocketFixture<T>> extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFixture.class);

    protected final String token;
    protected final List<String> receivedMessages = new CopyOnWriteArrayList<>();
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected final List<Rule> rules = new CopyOnWriteArrayList<>();
    protected final List<TypedRule<?>> typedRules = new CopyOnWriteArrayList<>();
    protected final Map<String, CountDownLatch> triggerLatches = new ConcurrentHashMap<>();
    private final Map<String, MessageLatch> messageLatches = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface RuleHandler<T> {
        void handle(WebSocketSession session, T payload) throws Exception;
    }

    public record Rule(Predicate<String> predicate, RuleHandler<String> handler) {
        public boolean matches(String payload) {
            return predicate.test(payload);
        }

        public void handle(WebSocketSession session, String payload) {
            if (handler != null) {
                try {
                    handler.handle(session, payload);
                } catch (Exception e) {
                    throw new RuntimeException("Error executing websocket rule handler", e);
                }
            }
        }
    }

    @FunctionalInterface
    public interface TypedRuleHandler<M extends ClientPayload> {
        void handle(WebSocketSession session, M message) throws Exception;
    }

    @FunctionalInterface
    public interface TypedRulePredicate<M extends ClientPayload> {
        boolean matches(M message);
    }

    public record TypedRule<M extends ClientPayload>(
            Class<M> messageType, TypedRulePredicate<M> predicate, TypedRuleHandler<M> handler) {

        public boolean matches(ClientPayload message) {
            return messageType.isInstance(message) && predicate.matches(messageType.cast(message));
        }

        public void handle(WebSocketSession session, ClientPayload message) {
            if (handler != null) {
                try {
                    handler.handle(session, messageType.cast(message));
                } catch (Exception e) {
                    throw new RuntimeException("Error executing typed rule handler", e);
                }
            }
        }
    }

    protected WebSocketFixture(String token) {
        this.token = token;
    }

    @SuppressWarnings("unchecked")
    public T when(Predicate<String> predicate, RuleHandler<String> handler) {
        this.rules.addFirst(new Rule(predicate, handler)); // Custom rules take precedence
        return (T) this;
    }

    public T whenContains(String substring, RuleHandler<String> handler) {
        return when(payload -> payload.contains(substring), handler);
    }

    public T whenMethod(String method, RuleHandler<String> handler) {
        return whenContains("\"method\":\"" + method + "\"", handler);
    }

    public <M extends ClientPayload> T whenType(Class<M> messageType, TypedRuleHandler<M> handler) {
        return whenType(messageType, msg -> true, handler);
    }

    @SuppressWarnings("unchecked")
    public <M extends ClientPayload> T whenType(
            Class<M> messageType, TypedRulePredicate<M> predicate, TypedRuleHandler<M> handler) {
        typedRules.addFirst(new TypedRule<>(messageType, predicate, handler));
        return (T) this;
    }

    /**
     * Check if any of the received messages contain all specified substrings.
     */
    public boolean hasReceivedMessageContaining(String... substrings) {
        return receivedMessages.stream().anyMatch(message -> {
            for (String sub : substrings) {
                if (!message.contains(sub)) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * Await a message containing all specified substrings. Registers a latch before checking
     * already-received messages, so a message arriving concurrently with the registration is never
     * missed.
     */
    public boolean awaitMessageContaining(long timeout, TimeUnit unit, String... substrings)
            throws InterruptedException {
        MessageLatch messageLatch =
                messageLatches.computeIfAbsent(List.of(substrings).toString(), key -> new MessageLatch(substrings));
        if (hasReceivedMessageContaining(substrings)) {
            messageLatch.latch.countDown();
        }
        return messageLatch.latch.await(timeout, unit);
    }

    /**
     * Await a specific substring in any received payload.
     * Automatically registers a latch if not already present.
     */
    public boolean awaitTrigger(String substring, long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = triggerLatches.computeIfAbsent(substring, s -> new CountDownLatch(1));
        return latch.await(timeout, unit);
    }

    /**
     * Pre-register a trigger latch with a specific expected count.
     */
    @SuppressWarnings("unchecked")
    public T expectTrigger(String substring, int count) {
        triggerLatches.put(substring, new CountDownLatch(count));
        return (T) this;
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        receivedMessages.add(payload);

        // Attempt typed deserialization
        ClientPayload typed = tryDeserialize(payload);

        if (typed != null) {
            // Route to typed rules first (they take precedence)
            for (TypedRule<?> rule : typedRules) {
                if (rule.matches(typed)) {
                    rule.handle(session, typed);
                    break;
                }
            }
        }

        // Fall back to legacy string-based rules (backward compatibility)
        for (Rule rule : rules) {
            if (rule.matches(payload)) {
                rule.handle(session, payload);
                break;
            }
        }

        // Check triggers and count down matching latches
        for (Map.Entry<String, CountDownLatch> entry : triggerLatches.entrySet()) {
            if (payload.contains(entry.getKey())) {
                entry.getValue().countDown();
            }
        }

        // Count down message latches whose substrings are all present in this payload
        for (MessageLatch messageLatch : messageLatches.values()) {
            if (messageLatch.matches(payload)) {
                messageLatch.latch.countDown();
            }
        }
    }

    private static final class MessageLatch {
        private final String[] substrings;
        private final CountDownLatch latch = new CountDownLatch(1);

        private MessageLatch(String[] substrings) {
            this.substrings = substrings;
        }

        private boolean matches(String payload) {
            for (String sub : substrings) {
                if (!payload.contains(sub)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Returns null if the payload is not a {@link ClientPayload} record. */
    private ClientPayload tryDeserialize(String payload) {
        try {
            JsonNode tree = objectMapper.readTree(payload);
            JsonNode resultNode = tree.get("result");
            if (resultNode != null && resultNode.has("type")) {
                ClientPayloadType payloadType =
                        ClientPayloadType.fromWire(resultNode.get("type").asText());
                return objectMapper.treeToValue(resultNode, payloadRecordClass(payloadType));
            }
        } catch (Exception e) {
            logger.debug("Could not deserialize payload as ClientPayload: {}", e.getMessage());
        }
        return null;
    }

    private static Class<? extends ClientPayload> payloadRecordClass(ClientPayloadType type) {
        return switch (type) {
            case AUTH -> ClientPayload.AuthResult.class;
            case PING -> ClientPayload.PingResult.class;
            case SUBSCRIPTION -> ClientPayload.SubscriptionResult.class;
            case CHAT_SUBSCRIPTION -> ClientPayload.ChatSubscriptionResult.class;
            case MESSAGE -> ClientPayload.MessageResult.class;
            case MESSAGE_CHUNK -> ClientPayload.MessageChunkResult.class;
            case TELEMETRY -> ClientPayload.TelemetryResult.class;
            case COMPLETE -> ClientPayload.CompleteResult.class;
            case CHAT_ERROR -> ClientPayload.ChatErrorResult.class;
            case CANVAS -> ClientPayload.CanvasResult.class;
            case INGESTION -> ClientPayload.IngestionResult.class;
            case TEAM_ENTITY_CHANGED -> ClientPayload.TeamEntityChangedResult.class;
            case USER_ENTITY_CHANGED -> ClientPayload.UserEntityChangedResult.class;
            case EXECUTION_HITL_REQUIRED -> ClientPayload.ExecutionHitlRequiredResult.class;
            case EXECUTION_HITL_RESOLVED -> ClientPayload.ExecutionHitlResolvedResult.class;
            case EXECUTION_OUTPUT -> ClientPayload.ExecutionOutputResult.class;
            case EXECUTION_COMPLETE -> ClientPayload.ExecutionCompleteResult.class;
            case EXECUTION_STATUS_CHANGED -> ClientPayload.ExecutionStatusChangedResult.class;
            case EXECUTION_ACTIVITY -> ClientPayload.ExecutionActivityResult.class;
            case EXECUTION_REPLAY_COMPLETE -> ClientPayload.ExecutionReplayCompleteResult.class;
            case EXECUTION_ACP_INITIALIZED -> ClientPayload.ExecutionAcpInitializedResult.class;
        };
    }
}
