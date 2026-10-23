package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityHitl;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResolver;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PendingHitlTimeoutService {

    private static final Logger logger = LoggerFactory.getLogger(PendingHitlTimeoutService.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final SandboxExecutionActivityRepository activityRepository;
    private final SandboxExecutionRepository executionRepository;
    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final SandboxExecutionService sandboxExecutionService;

    public PendingHitlTimeoutService(
            SandboxExecutionActivityRepository activityRepository,
            SandboxExecutionRepository executionRepository,
            ExecutionActivityPersistenceService activityPersistenceService,
            ApplicationEventPublisher eventPublisher,
            SandboxExecutionService sandboxExecutionService) {
        this.activityRepository = activityRepository;
        this.executionRepository = executionRepository;
        this.activityPersistenceService = activityPersistenceService;
        this.eventPublisher = eventPublisher;
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupExpiredHitl() {
        Instant cutoff = Instant.now().minus(TIMEOUT);
        List<SandboxExecutionActivity> expired =
                activityRepository.findByStatusAndCreatedAtBefore(ActivityStatus.PENDING, cutoff);

        for (SandboxExecutionActivity activity : expired) {
            UUID executionId = activity.getExecutionId();
            UUID teamId = executionRepository
                    .findById(executionId)
                    .map(execution -> execution.getChat().getTeam().getId())
                    .orElse(null);
            if (teamId == null) {
                logger.warn("Skipping HITL timeout for missing execution {}", executionId);
                continue;
            }

            ActivityDetail detail = activityPersistenceService.detailOf(activity);
            ActivityHitl hitl = detail != null ? detail.hitl() : null;
            HitlKind kind = hitl != null && hitl.kind() != null ? hitl.kind() : HitlKind.APPROVAL;
            String message = hitl != null && hitl.message() != null ? hitl.message() : activity.getDescription();

            logger.info(
                    "Timing out pending HITL request for execution {} (actionId='{}', kind={}, team={})",
                    executionId,
                    activity.getActionId(),
                    kind,
                    teamId);

            eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                    teamId,
                    new HitlResolution(
                            executionId,
                            activity.getActionId(),
                            kind,
                            HitlResponse.CANCELLED,
                            null,
                            null,
                            HitlResolver.timeout())));

            String prompt = timeoutPrompt(kind, message);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        sandboxExecutionService.dispatchSystemSteering(executionId, prompt);
                    } catch (Exception e) {
                        logger.warn("Failed to dispatch timeout steering for execution {}", executionId, e);
                    }
                }
            });
        }
    }

    static String timeoutPrompt(HitlKind kind, String message) {
        String subject = kind == HitlKind.QUESTION ? "question" : "approval";
        String quoted = message == null || message.isBlank() ? "" : " (\"" + abbreviate(message, 200) + "\")";
        return ("System notice: the %s request%s timed out because no response was received; nobody was available "
                        + "to answer, so this is not a rejection. Continue the task without performing that action, "
                        + "or choose a safe alternative if it was essential.")
                .formatted(subject, quoted);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
