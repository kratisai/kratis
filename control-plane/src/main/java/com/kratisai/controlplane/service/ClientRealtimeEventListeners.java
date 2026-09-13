package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.CanvasEvent;
import com.kratisai.controlplane.api.wsdto.ClientPayload.*;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.IngestionEvent;
import com.kratisai.controlplane.api.wsdto.IngestionStatusEvent;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.*;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ClientRealtimeEventListeners {

    private static final Logger logger = LoggerFactory.getLogger(ClientRealtimeEventListeners.class);

    private final WebSocketDispatch dispatch;

    public ClientRealtimeEventListeners(WebSocketDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestionStatusEvent(IngestionStatusEvent event) {
        IngestionBatch batch = event.batch();
        try {
            UUID repositoryId = batch.getRepository().getId();
            Team team = batch.getRepository().getTeam();

            IngestionEvent.StatusUpdate statusEvent = new IngestionEvent.StatusUpdate(
                    repositoryId, batch.getId(), batch.getStatus(), batch.getCommitHash(), batch.getCompletedAt());

            logger.info(
                    "Publishing ingestion status update {} for batch {} (repository {}, team {})",
                    batch.getStatus(),
                    batch.getId(),
                    repositoryId,
                    team.getId());

            dispatch.broadcastNotificationToTeam(team.getId(), new IngestionResult(statusEvent));
        } catch (Exception e) {
            logger.error("Failed to publish ingestion status update for batch {}", batch.getId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamEntityChangedEvent(TeamEntityChangedEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(), new TeamEntityChangedResult(event.teamId(), event.type()));
        } catch (Exception e) {
            logger.error("Failed to publish team entity changed event for team {}", event.teamId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserEntityChangedEvent(UserEntityChangedEvent event) {
        try {
            dispatch.broadcastNotificationToUser(
                    event.userId(), new UserEntityChangedResult(event.userId(), event.type()));
        } catch (Exception e) {
            logger.error("Failed to publish user entity changed event for user {}", event.userId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionOutputEvent(SandboxExecutionOutputEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(), new ExecutionOutputResult(event.executionId(), event.line(), event.stream()));
        } catch (Exception e) {
            logger.error("Failed to publish execution output event for execution {}", event.executionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionActivityEvent(SandboxExecutionActivityEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(),
                    new ExecutionActivityResult(
                            event.executionId(),
                            event.activityType(),
                            event.description(),
                            event.actionId(),
                            event.status(),
                            event.detail()));
        } catch (Exception e) {
            logger.error("Failed to publish execution activity event for execution {}", event.executionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionHitlRequiredEvent(SandboxExecutionHitlRequiredEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(),
                    new ExecutionHitlRequiredResult(
                            event.executionId(),
                            event.hitlId(),
                            event.kind(),
                            event.message(),
                            event.command(),
                            event.title(),
                            event.toolKind(),
                            event.options(),
                            event.diff(),
                            event.form()));
        } catch (Exception e) {
            logger.error(
                    "Failed to publish HITL required event for execution {} (hitlId='{}', kind={})",
                    event.executionId(),
                    event.hitlId(),
                    event.kind(),
                    e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionHitlResolvedEvent(SandboxExecutionHitlResolvedEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(),
                    new ExecutionHitlResolvedResult(
                            event.executionId(),
                            event.hitlId(),
                            event.kind() != null ? event.kind() : HitlKind.APPROVAL,
                            event.response(),
                            event.optionId(),
                            event.content(),
                            event.resolvedByUserId(),
                            event.resolvedByDisplayName()));
        } catch (Exception e) {
            logger.error(
                    "Failed to publish HITL resolved event for execution {} (hitlId='{}')",
                    event.executionId(),
                    event.hitlId(),
                    e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionCompleteEvent(SandboxExecutionCompleteEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(), new ExecutionCompleteResult(event.executionId(), event.exitCode(), event.status()));
        } catch (Exception e) {
            logger.error("Failed to publish execution complete event for execution {}", event.executionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExecutionStatusChangedEvent(ExecutionStatusChangedEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(),
                    new ExecutionStatusChangedResult(event.teamId(), event.chatId(), event.executionId()));
        } catch (Exception e) {
            logger.error("Failed to publish execution status changed event for execution {}", event.executionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCanvasDeletedEvent(CanvasDeletedEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(), new CanvasResult(new CanvasEvent.Delete(event.documentId(), event.chatId())));
        } catch (Exception e) {
            logger.error("Failed to publish canvas deleted event for chat {}", event.chatId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSandboxExecutionAcpInitializedEvent(SandboxExecutionAcpInitializedEvent event) {
        try {
            dispatch.broadcastNotificationToTeam(
                    event.teamId(),
                    new ExecutionAcpInitializedResult(
                            event.executionId(), event.sessionId(), event.agentName(), event.agentVersion()));
        } catch (Exception e) {
            logger.error("Failed to publish execution acp initialized event for execution {}", event.executionId(), e);
        }
    }
}
