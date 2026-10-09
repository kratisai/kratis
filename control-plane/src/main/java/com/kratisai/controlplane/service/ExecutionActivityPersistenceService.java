package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityHitl;
import com.kratisai.controlplane.api.wsdto.ActivityKind;
import com.kratisai.controlplane.api.wsdto.ActivityLocation;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlRequestSnapshot;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.HitlState;
import com.kratisai.controlplane.api.wsdto.StopReason;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class ExecutionActivityPersistenceService {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionActivityPersistenceService.class);

    private static final String ERROR_ACTION_ID = "execution-error";
    private static final int MAX_ERROR_DESCRIPTION = 2000;

    private final SandboxExecutionActivityRepository repository;
    private final SandboxExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ExecutionActivityPersistenceService(
            SandboxExecutionActivityRepository repository,
            SandboxExecutionRepository executionRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public void recordActivity(
            UUID executionId,
            ActivityType activityType,
            String description,
            String actionId,
            ActivityStatus status,
            ActivityDetail detail) {
        ActivityDetail lifecycle = detail != null ? detail.withHitl(null) : null;
        if (actionId == null || actionId.isBlank()) {
            insert(executionId, activityType, description, null, status, lifecycle);
            return;
        }
        repository
                .findByExecutionIdAndActionId(executionId, actionId)
                .ifPresentOrElse(
                        existing -> merge(existing, activityType, description, status, lifecycle),
                        () -> insert(executionId, activityType, description, actionId, status, lifecycle));
    }

    public List<SandboxExecutionActivity> getActivities(UUID executionId) {
        return repository.findByExecutionIdOrderBySequenceAsc(executionId);
    }

    public ActivityDetail detailOf(SandboxExecutionActivity activity) {
        String detail = activity.getDetail();
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(detail, ActivityDetail.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored activity detail is not valid JSON", e);
        }
    }

    public void closeOpenStreams(UUID executionId) {
        List<SandboxExecutionActivity> open =
                repository.findByExecutionIdAndStatusOrderBySequenceAsc(executionId, ActivityStatus.IN_PROGRESS);
        if (open.isEmpty()) {
            return;
        }
        open.forEach(activity -> activity.setStatus(ActivityStatus.COMPLETED));
        repository.saveAll(open);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onHitlRequired(SandboxExecutionHitlRequiredEvent event) {
        if (event.result().kind() == HitlKind.APPROVAL) {
            onApprovalRequired(event);
        } else {
            onQuestionRequired(event);
        }
    }

    private void onApprovalRequired(SandboxExecutionHitlRequiredEvent event) {
        HitlRequestSnapshot request = event.result();
        applyApproval(
                event.teamId(), request, ActivityHitl.from(request, HitlState.AWAITING_HUMAN), ActivityStatus.PENDING);
    }

    @Transactional
    public void recordAutoResolved(UUID teamId, HitlRequestSnapshot request, HitlResolution resolution) {
        ActivityStatus status =
                resolution.response() == HitlResponse.APPROVED ? ActivityStatus.IN_PROGRESS : ActivityStatus.FAILED;
        applyApproval(
                teamId,
                request,
                ActivityHitl.from(request, HitlState.AWAITING_HUMAN).withResolution(resolution),
                status);
    }

    private void applyApproval(UUID teamId, HitlRequestSnapshot request, ActivityHitl hitl, ActivityStatus status) {
        SandboxExecutionActivity row = repository
                .findByExecutionIdAndActionId(request.executionId(), request.hitlId())
                .map(existing -> {
                    ActivityDetail detail = detailOf(existing);
                    existing.setStatus(status);
                    existing.setDetail(toJson((detail != null ? detail : emptyDetail()).withHitl(hitl)));
                    repository.save(existing);
                    return existing;
                })
                .orElseGet(() -> {
                    logger.warn(
                            "Approval {} has no activity row for execution {}; inserting placeholder",
                            request.hitlId(),
                            request.executionId());
                    return insert(
                            request.executionId(),
                            placeholderType(request.toolKind()),
                            request.command() != null ? request.command() : request.message(),
                            request.hitlId(),
                            status,
                            placeholderDetail(request).withHitl(hitl));
                });
        publishActivity(teamId, row);
    }

    private void onQuestionRequired(SandboxExecutionHitlRequiredEvent event) {
        HitlRequestSnapshot request = event.result();
        SandboxExecutionActivity row = insert(
                request.executionId(),
                ActivityType.ELICITATION,
                request.message(),
                request.hitlId(),
                ActivityStatus.PENDING,
                emptyDetail().withHitl(ActivityHitl.from(request, HitlState.AWAITING_HUMAN)));
        publishActivity(event.teamId(), row);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onHitlResolved(SandboxExecutionHitlResolvedEvent event) {
        if (event.result().kind() == HitlKind.APPROVAL) {
            onApprovalResolved(event);
        } else {
            onQuestionResolved(event);
        }
    }

    private void onApprovalResolved(SandboxExecutionHitlResolvedEvent event) {
        HitlResolution resolution = event.result();
        repository
                .findByExecutionIdAndActionId(resolution.executionId(), resolution.hitlId())
                .ifPresent(activity -> {
                    boolean approved = resolution.response() == HitlResponse.APPROVED;
                    activity.setStatus(approved ? ActivityStatus.IN_PROGRESS : ActivityStatus.FAILED);
                    ActivityDetail detail = detailOf(activity);
                    if (detail != null && detail.hitl() != null) {
                        activity.setDetail(toJson(detail.withHitl(detail.hitl().withResolution(resolution))));
                    }
                    repository.save(activity);
                    publishActivity(event.teamId(), activity);
                });
    }

    private void onQuestionResolved(SandboxExecutionHitlResolvedEvent event) {
        HitlResolution resolution = event.result();
        repository
                .findFirstByExecutionIdAndActionIdOrderBySequenceDesc(resolution.executionId(), resolution.hitlId())
                .ifPresent(activity -> {
                    ActivityDetail detail = detailOf(activity);
                    ActivityHitl answered = detail != null && detail.hitl() != null
                            ? detail.hitl().withResolution(resolution)
                            : ActivityHitl.from(resolution);
                    ActivityDetail resolved = detail != null
                            ? detail.withHitl(answered)
                            : emptyDetail().withHitl(answered);
                    activity.setDetail(toJson(resolved));
                    activity.setStatus(
                            resolution.response() == HitlResponse.ANSWERED
                                            || resolution.response() == HitlResponse.APPROVED
                                    ? ActivityStatus.COMPLETED
                                    : ActivityStatus.FAILED);
                    repository.save(activity);
                    publishActivity(event.teamId(), activity);
                });
    }

    private static ActivityDetail emptyDetail() {
        return new ActivityDetail(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ActivityDetail placeholderDetail(HitlRequestSnapshot request) {
        ActivityKind kind = ActivityKind.fromWireValue(request.toolKind()).orElse(null);
        ActivityLocation location = request.diff() != null && request.diff().path() != null
                ? new ActivityLocation(request.diff().path(), null)
                : null;
        List<ActivityLocation> locations = location != null ? List.of(location) : null;
        return new ActivityDetail(
                kind,
                request.title(),
                locations,
                null,
                null,
                request.diff(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static ActivityType placeholderType(String toolKind) {
        return ActivityKind.isCommandLike(toolKind) ? ActivityType.COMMAND : ActivityType.EDITED;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onExecutionComplete(SandboxExecutionCompleteEvent event) {
        closeOpenStreams(event.executionId());
    }

    @Transactional
    public void recordTurnComplete(UUID executionId, StopReason stopReason) {
        String description = "Agent turn complete (" + (stopReason != null ? stopReason.getValue() : "unknown") + ")";
        recordActivity(executionId, ActivityType.MESSAGE, description, null, ActivityStatus.COMPLETED, null);
        executionRepository
                .findById(executionId)
                .map(execution -> execution.getChat().getTeam().getId())
                .ifPresent(teamId -> eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                        teamId, executionId, ActivityType.MESSAGE, description, null, ActivityStatus.COMPLETED, null)));
    }

    @Transactional
    public void recordHarnessSleep(UUID executionId) {
        SandboxExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            return;
        }
        AgentHarness harness = execution.getHarness();
        String description = (harness != null ? harness.getName() : "Agent") + " has gone to sleep";
        recordActivity(executionId, ActivityType.MESSAGE, description, null, ActivityStatus.COMPLETED, null);
        eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                execution.getChat().getTeam().getId(),
                executionId,
                ActivityType.MESSAGE,
                description,
                null,
                ActivityStatus.COMPLETED,
                null));
    }

    @Transactional
    public void recordExecutionError(UUID executionId, String reason, Integer exitCode) {
        String description = reason != null && !reason.isBlank()
                ? truncate(reason)
                : "Execution failed (exit code " + exitCode + ")";
        ActivityDetail detail = new ActivityDetail(
                null, null, null, null, null, null, exitCode, null, null, null, null, null, null, null);

        recordActivity(executionId, ActivityType.ERROR, description, ERROR_ACTION_ID, ActivityStatus.FAILED, detail);

        Optional<UUID> teamId = executionRepository
                .findById(executionId)
                .map(execution -> execution.getChat().getTeam().getId());
        teamId.ifPresent(uuid -> eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                uuid, executionId, ActivityType.ERROR, description, ERROR_ACTION_ID, ActivityStatus.FAILED, detail)));
    }

    private static String truncate(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= MAX_ERROR_DESCRIPTION ? trimmed : trimmed.substring(0, MAX_ERROR_DESCRIPTION);
    }

    private SandboxExecutionActivity insert(
            UUID executionId,
            ActivityType activityType,
            String description,
            String actionId,
            ActivityStatus status,
            ActivityDetail detail) {
        SandboxExecutionActivity row = new SandboxExecutionActivity(
                executionId,
                repository.nextSequence(executionId),
                actionId,
                activityType,
                status,
                description,
                toJson(detail));
        repository.save(row);
        return row;
    }

    private void publishActivity(UUID teamId, SandboxExecutionActivity row) {
        eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                teamId,
                row.getExecutionId(),
                row.getActivityType(),
                row.getDescription(),
                row.getActionId(),
                row.getStatus(),
                detailOf(row)));
    }

    private void merge(
            SandboxExecutionActivity existing,
            ActivityType activityType,
            String description,
            ActivityStatus status,
            ActivityDetail detail) {
        existing.setActivityType(activityType);
        existing.setDescription(description);
        existing.setStatus(status);
        if (detail != null) {
            ActivityDetail current = detailOf(existing);
            boolean keepApproval = current != null && current.hitl() != null;
            existing.setDetail(toJson(keepApproval ? detail.withHitl(current.hitl()) : detail));
        }
        repository.save(existing);
    }

    private String toJson(ActivityDetail detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Activity detail cannot be serialized", e);
        }
    }
}
