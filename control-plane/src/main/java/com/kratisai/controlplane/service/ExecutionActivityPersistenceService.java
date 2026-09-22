package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityHitl;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.event.SandboxExecutionCompleteEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlRequiredEvent;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class ExecutionActivityPersistenceService {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionActivityPersistenceService.class);

    private final SandboxExecutionActivityRepository repository;
    private final ObjectMapper objectMapper;

    public ExecutionActivityPersistenceService(
            SandboxExecutionActivityRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void recordActivity(
            UUID executionId,
            ActivityType activityType,
            String description,
            String actionId,
            ActivityStatus status,
            ActivityDetail detail) {
        if (actionId == null || actionId.isBlank()) {
            insert(executionId, activityType, description, null, status, detail);
            return;
        }
        repository
                .findByExecutionIdAndActionId(executionId, actionId)
                .ifPresentOrElse(
                        existing -> merge(existing, activityType, description, status, detail),
                        () -> insert(executionId, activityType, description, actionId, status, detail));
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
        ExecutionHitlRequiredResult request = event.result();
        ActivityHitl hitl = ActivityHitl.from(request);
        repository
                .findByExecutionIdAndActionId(request.executionId(), request.hitlId())
                .ifPresentOrElse(
                        existing -> {
                            ActivityDetail detail = detailOf(existing);
                            ActivityDetail merged = detail != null
                                    ? detail.withHitl(hitl)
                                    : emptyDetail().withHitl(hitl);
                            existing.setStatus(ActivityStatus.PENDING);
                            existing.setDetail(toJson(merged));
                            repository.save(existing);
                        },
                        () -> logger.warn(
                                "Approval {} has no activity row for execution {}; ignored",
                                request.hitlId(),
                                request.executionId()));
    }

    private void onQuestionRequired(SandboxExecutionHitlRequiredEvent event) {
        ExecutionHitlRequiredResult request = event.result();
        ActivityDetail detail = new ActivityDetail(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ActivityHitl.from(request));
        recordActivity(
                request.executionId(),
                ActivityType.ELICITATION,
                request.message(),
                request.hitlId(),
                ActivityStatus.PENDING,
                detail);
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
        ExecutionHitlResolvedResult resolution = event.result();
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
                });
    }

    private void onQuestionResolved(SandboxExecutionHitlResolvedEvent event) {
        ExecutionHitlResolvedResult resolution = event.result();
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
                });
    }

    private static ActivityDetail emptyDetail() {
        return new ActivityDetail(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onExecutionComplete(SandboxExecutionCompleteEvent event) {
        closeOpenStreams(event.executionId());
    }

    private void insert(
            UUID executionId,
            ActivityType activityType,
            String description,
            String actionId,
            ActivityStatus status,
            ActivityDetail detail) {
        repository.save(new SandboxExecutionActivity(
                executionId,
                repository.nextSequence(executionId),
                actionId,
                activityType,
                status,
                description,
                toJson(detail)));
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
            existing.setDetail(toJson(detail));
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
