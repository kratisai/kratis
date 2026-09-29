package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kratisai.controlplane.api.wsprotocol.Direction;
import com.kratisai.controlplane.api.wsprotocol.MessageKind;
import java.util.Objects;

/**
 * Sealed set of every web-UI ({@code /ws/client}) request payload.
 */
public sealed interface ClientRpcPayload extends RpcPayload {

    @Override
    default MessageKind messageKind() {
        return MessageKind.REQUEST;
    }

    @Override
    default Direction direction() {
        return Direction.WEB_TO_CONTROL_PLANE;
    }

    /** Authenticate the WebSocket connection with a JWT token. */
    record Auth(@JsonProperty("token") String token) implements ClientRpcPayload {
        public static final String METHOD = "auth";

        @Override
        public String method() {
            return METHOD;
        }

        public Auth {
            Objects.requireNonNull(token, "token is required");
        }
    }

    /** Client keepalive ping. */
    record Ping() implements ClientRpcPayload, NoParamsPayload {
        public static final String METHOD = "ping";

        @Override
        public String method() {
            return METHOD;
        }
    }

    /** Subscribe to team event updates. */
    record Subscribe(@JsonProperty("teamId") String teamId) implements ClientRpcPayload {
        public static final String METHOD = "subscribe";

        @Override
        public String method() {
            return METHOD;
        }

        public Subscribe {
            Objects.requireNonNull(teamId, "teamId is required");
        }
    }

    /** Unsubscribe from team event updates. */
    record Unsubscribe(@JsonProperty("teamId") String teamId) implements ClientRpcPayload {
        public static final String METHOD = "unsubscribe";

        @Override
        public String method() {
            return METHOD;
        }

        public Unsubscribe {
            Objects.requireNonNull(teamId, "teamId is required");
        }
    }

    /** Send a chat prompt message. */
    record ChatSend(
            @JsonProperty("message") String message,
            @JsonProperty("teamId") String teamId,
            @JsonProperty("providerId") String providerId,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("chatId") String chatId)
            implements ClientRpcPayload {
        public static final String METHOD = "chat.send";

        @Override
        public String method() {
            return METHOD;
        }

        public ChatSend {
            Objects.requireNonNull(message, "message is required");
            Objects.requireNonNull(teamId, "teamId is required");
            Objects.requireNonNull(providerId, "providerId is required");
            Objects.requireNonNull(modelName, "modelName is required");
            Objects.requireNonNull(chatId, "chatId is required");
        }
    }

    /** Subscribe to chat updates and load history. */
    record ChatSubscribe(
            @JsonProperty("chatId") String chatId,
            @JsonProperty("teamId") String teamId) implements ClientRpcPayload {
        public static final String METHOD = "chat.subscribe";

        @Override
        public String method() {
            return METHOD;
        }

        public ChatSubscribe {
            Objects.requireNonNull(chatId, "chatId is required");
        }
    }

    /** Unsubscribe from chat updates. */
    record ChatUnsubscribe(@JsonProperty("chatId") String chatId) implements ClientRpcPayload {
        public static final String METHOD = "chat.unsubscribe";

        @Override
        public String method() {
            return METHOD;
        }

        public ChatUnsubscribe {
            Objects.requireNonNull(chatId, "chatId is required");
        }
    }

    /** Replay persisted activities for an execution (running or completed). */
    record ExecutionReplayActivities(
            @JsonProperty("executionId") String executionId) implements ClientRpcPayload {
        public static final String METHOD = "execution.replay_activities";

        @Override
        public String method() {
            return METHOD;
        }

        public ExecutionReplayActivities {
            Objects.requireNonNull(executionId, "executionId is required");
        }
    }
}
