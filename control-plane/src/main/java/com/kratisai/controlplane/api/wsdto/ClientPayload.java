package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.model.event.UserEntityType;
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public sealed interface ClientPayload {

    sealed interface ChatStreamPayload extends ClientPayload {}

    sealed interface ChatHistoryPayload extends ClientPayload {}

    record AuthResult(ClientPayloadType type, AuthStatus status, String userId) implements ClientPayload {
        public AuthResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(userId, "userId is required");
        }

        public AuthResult(String userId) {
            this(ClientPayloadType.AUTH, AuthStatus.AUTHENTICATED, userId);
        }
    }

    record PingResult(ClientPayloadType type, PingStatus status) implements ClientPayload {
        public PingResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
        }

        public PingResult() {
            this(ClientPayloadType.PING, PingStatus.PONG);
        }
    }

    record MessageResult(
            ClientPayloadType type, String content, UUID chatId, String messageId, MessageRole role, String timestamp)
            implements ClientPayload, ChatHistoryPayload {

        public MessageResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(content, "content is required");
            Objects.requireNonNull(chatId, "chatId is required");
            Objects.requireNonNull(messageId, "messageId is required");
            Objects.requireNonNull(role, "role is required");
            Objects.requireNonNull(timestamp, "timestamp is required");
        }

        public static MessageResult replay(
                UUID chatId, String messageId, MessageRole role, String content, String timestamp) {
            return new MessageResult(ClientPayloadType.MESSAGE, content, chatId, messageId, role, timestamp);
        }
    }

    record MessageChunkResult(
            ClientPayloadType type, String content, UUID chatId, String messageId, MessageRole role, String timestamp)
            implements ClientPayload, ChatStreamPayload {

        public MessageChunkResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(content, "content is required");
            Objects.requireNonNull(chatId, "chatId is required");
            Objects.requireNonNull(messageId, "messageId is required");
            Objects.requireNonNull(role, "role is required");
            Objects.requireNonNull(timestamp, "timestamp is required");
        }

        public MessageChunkResult(UUID chatId, String messageId, String content) {
            this(
                    ClientPayloadType.MESSAGE_CHUNK,
                    content,
                    chatId,
                    messageId,
                    MessageRole.ASSISTANT,
                    Instant.now().toString());
        }
    }

    record TelemetryResult(ClientPayloadType type, UUID chatId, TelemetryEvent event)
            implements ClientPayload, ChatStreamPayload {
        public TelemetryResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(chatId, "chatId is required");
            Objects.requireNonNull(event, "event is required");
        }

        public TelemetryResult(UUID chatId, TelemetryEvent event) {
            this(ClientPayloadType.TELEMETRY, chatId, event);
        }
    }

    record CompleteResult(ClientPayloadType type, String messageId, UUID chatId, int messageCount)
            implements ClientPayload, ChatStreamPayload, ChatHistoryPayload {

        public CompleteResult {
            Objects.requireNonNull(type, "type is required");
        }

        public CompleteResult(String messageId) {
            this(ClientPayloadType.COMPLETE, messageId, null, 0);
        }

        public CompleteResult(UUID chatId, int messageCount) {
            this(ClientPayloadType.COMPLETE, null, chatId, messageCount);
        }
    }

    record ChatErrorResult(ClientPayloadType type, UUID chatId, String messageId, int code, String message)
            implements ClientPayload, ChatStreamPayload {
        public ChatErrorResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(chatId, "chatId is required");
            Objects.requireNonNull(messageId, "messageId is required");
            Objects.requireNonNull(message, "message is required");
        }

        public ChatErrorResult(UUID chatId, String messageId, int code, String message) {
            this(ClientPayloadType.CHAT_ERROR, chatId, messageId, code, message);
        }
    }

    record CanvasResult(ClientPayloadType type, CanvasEvent event)
            implements ClientPayload, ChatStreamPayload, ChatHistoryPayload {
        public CanvasResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(event, "event is required");
        }

        public CanvasResult(CanvasEvent event) {
            this(ClientPayloadType.CANVAS, event);
        }
    }

    record IngestionResult(ClientPayloadType type, IngestionEvent event) implements ClientPayload {
        public IngestionResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(event, "event is required");
        }

        public IngestionResult(IngestionEvent event) {
            this(ClientPayloadType.INGESTION, event);
        }
    }

    record TeamEntityChangedResult(ClientPayloadType type, UUID teamId, TeamEntityType entity)
            implements ClientPayload {
        public TeamEntityChangedResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(teamId, "teamId is required");
            Objects.requireNonNull(entity, "entity is required");
        }

        public TeamEntityChangedResult(UUID teamId, TeamEntityType entity) {
            this(ClientPayloadType.TEAM_ENTITY_CHANGED, teamId, entity);
        }
    }

    record UserEntityChangedResult(ClientPayloadType type, UUID userId, UserEntityType entity)
            implements ClientPayload {
        public UserEntityChangedResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(userId, "userId is required");
            Objects.requireNonNull(entity, "entity is required");
        }

        public UserEntityChangedResult(UUID userId, UserEntityType entity) {
            this(ClientPayloadType.USER_ENTITY_CHANGED, userId, entity);
        }
    }

    record SubscriptionResult(ClientPayloadType type, String channel, String message) implements ClientPayload {
        public SubscriptionResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(channel, "channel is required");
            Objects.requireNonNull(message, "message is required");
        }

        public SubscriptionResult(String channel, String message) {
            this(ClientPayloadType.SUBSCRIPTION, channel, message);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExecutionHitlRequiredResult(
            ClientPayloadType type,
            UUID executionId,
            String hitlId,
            HitlKind kind,
            String message,
            String command,
            List<CommandSegment> commandSegments,
            String title,
            String toolKind,
            List<PermissionOption> options,
            ActivityDiff diff,
            Map<String, Object> form)
            implements ClientPayload {
        public ExecutionHitlRequiredResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(hitlId, "hitlId is required");
            Objects.requireNonNull(kind, "kind is required");
            Objects.requireNonNull(message, "message is required");
        }

        public ExecutionHitlRequiredResult(
                UUID executionId,
                String hitlId,
                HitlKind kind,
                String message,
                String command,
                List<CommandSegment> commandSegments,
                String title,
                String toolKind,
                List<PermissionOption> options,
                ActivityDiff diff,
                Map<String, Object> form) {
            this(
                    ClientPayloadType.EXECUTION_HITL_REQUIRED,
                    executionId,
                    hitlId,
                    kind,
                    message,
                    command,
                    commandSegments,
                    title,
                    toolKind,
                    options,
                    diff,
                    form);
        }

        public ExecutionHitlRequiredResult(
                UUID executionId,
                String hitlId,
                HitlKind kind,
                String message,
                String command,
                String title,
                String toolKind,
                List<PermissionOption> options,
                ActivityDiff diff,
                Map<String, Object> form) {
            this(executionId, hitlId, kind, message, command, null, title, toolKind, options, diff, form);
        }

        public ExecutionHitlRequiredResult(UUID executionId, String hitlId, HitlKind kind, String message) {
            this(executionId, hitlId, kind, message, null, null, null, null, null, null, null);
        }
    }

    record ExecutionHitlResolvedResult(
            ClientPayloadType type,
            UUID executionId,
            String hitlId,
            HitlKind kind,
            HitlResponse response,
            String optionId,
            Map<String, Object> content,
            UUID resolvedByUserId,
            String resolvedByDisplayName)
            implements ClientPayload {
        public ExecutionHitlResolvedResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(hitlId, "hitlId is required");
            Objects.requireNonNull(kind, "kind is required");
            Objects.requireNonNull(response, "response is required");
        }

        public ExecutionHitlResolvedResult(
                UUID executionId,
                String hitlId,
                HitlKind kind,
                HitlResponse response,
                String optionId,
                Map<String, Object> content,
                UUID resolvedByUserId,
                String resolvedByDisplayName) {
            this(
                    ClientPayloadType.EXECUTION_HITL_RESOLVED,
                    executionId,
                    hitlId,
                    kind,
                    response,
                    optionId,
                    content,
                    resolvedByUserId,
                    resolvedByDisplayName);
        }
    }

    record ExecutionOutputResult(ClientPayloadType type, UUID executionId, String line, OutputStream stream)
            implements ClientPayload {
        public ExecutionOutputResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(line, "line is required");
            Objects.requireNonNull(stream, "stream is required");
        }

        public ExecutionOutputResult(UUID executionId, String line, OutputStream stream) {
            this(ClientPayloadType.EXECUTION_OUTPUT, executionId, line, stream);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExecutionCompleteResult(
            ClientPayloadType type, UUID executionId, int exitCode, SandboxExecutionStatus status, String reason)
            implements ClientPayload {
        public ExecutionCompleteResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(status, "status is required");
        }

        public ExecutionCompleteResult(UUID executionId, int exitCode, SandboxExecutionStatus status) {
            this(ClientPayloadType.EXECUTION_COMPLETE, executionId, exitCode, status, null);
        }

        public ExecutionCompleteResult(UUID executionId, int exitCode, SandboxExecutionStatus status, String reason) {
            this(ClientPayloadType.EXECUTION_COMPLETE, executionId, exitCode, status, reason);
        }
    }

    record ExecutionStatusChangedResult(ClientPayloadType type, UUID teamId, UUID chatId, UUID executionId)
            implements ClientPayload {
        public ExecutionStatusChangedResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(teamId, "teamId is required");
            Objects.requireNonNull(chatId, "chatId is required");
            Objects.requireNonNull(executionId, "executionId is required");
        }

        public ExecutionStatusChangedResult(UUID teamId, UUID chatId, UUID executionId) {
            this(ClientPayloadType.EXECUTION_STATUS_CHANGED, teamId, chatId, executionId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExecutionActivityResult(
            ClientPayloadType type,
            UUID executionId,
            ActivityType activityType,
            String description,
            String actionId,
            ActivityStatus status,
            ActivityDetail detail)
            implements ClientPayload {
        public ExecutionActivityResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(activityType, "activityType is required");
            Objects.requireNonNull(description, "description is required");
            Objects.requireNonNull(status, "status is required");
        }

        public ExecutionActivityResult(
                UUID executionId, ActivityType activityType, String description, ActivityStatus status) {
            this(ClientPayloadType.EXECUTION_ACTIVITY, executionId, activityType, description, null, status, null);
        }

        public ExecutionActivityResult(
                UUID executionId,
                ActivityType activityType,
                String description,
                String actionId,
                ActivityStatus status) {
            this(ClientPayloadType.EXECUTION_ACTIVITY, executionId, activityType, description, actionId, status, null);
        }

        public ExecutionActivityResult(
                UUID executionId,
                ActivityType activityType,
                String description,
                String actionId,
                ActivityStatus status,
                ActivityDetail detail) {
            this(
                    ClientPayloadType.EXECUTION_ACTIVITY,
                    executionId,
                    activityType,
                    description,
                    actionId,
                    status,
                    detail);
        }
    }

    record ExecutionReplayCompleteResult(ClientPayloadType type, UUID executionId, Integer activityCount)
            implements ClientPayload {
        public ExecutionReplayCompleteResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(activityCount, "activityCount is required");
        }

        public ExecutionReplayCompleteResult(UUID executionId, int activityCount) {
            this(ClientPayloadType.EXECUTION_REPLAY_COMPLETE, executionId, activityCount);
        }
    }

    record ChatSubscriptionResult(ClientPayloadType type, String status, UUID chatId) implements ClientPayload {
        public ChatSubscriptionResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(chatId, "chatId is required");
        }

        public ChatSubscriptionResult(String status, UUID chatId) {
            this(ClientPayloadType.CHAT_SUBSCRIPTION, status, chatId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExecutionAcpInitializedResult(
            ClientPayloadType type, UUID executionId, String sessionId, String agentName, String agentVersion)
            implements ClientPayload {
        public ExecutionAcpInitializedResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(executionId, "executionId is required");
            Objects.requireNonNull(sessionId, "sessionId is required");
            Objects.requireNonNull(agentName, "agentName is required");
            Objects.requireNonNull(agentVersion, "agentVersion is required");
        }

        public ExecutionAcpInitializedResult(
                UUID executionId, String sessionId, String agentName, String agentVersion) {
            this(ClientPayloadType.EXECUTION_ACP_INITIALIZED, executionId, sessionId, agentName, agentVersion);
        }
    }
}
