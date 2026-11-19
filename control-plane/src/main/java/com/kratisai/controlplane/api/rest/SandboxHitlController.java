package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.ResolveHitlRequest;
import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityHitl;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResolution;
import com.kratisai.controlplane.api.wsdto.HitlResolver;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.model.HitlRule;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.HitlRuleRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import com.kratisai.controlplane.service.SandboxExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hitl")
@Tag(name = "HITL", description = "Resolve pending human-in-the-loop requests (approvals + questions)")
@SecurityRequirement(name = "bearerAuth")
public class SandboxHitlController {

    private static final Logger logger = LoggerFactory.getLogger(SandboxHitlController.class);

    static final String DISMISSED_SUGGESTION_NOTICE =
            "System notice: the agent's suggestion was dismissed without a follow-up being applied; "
                    + "nothing to act on. Continue the task appropriately or wait for explicit direction.";

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final HitlRuleRepository hitlRuleRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final SandboxExecutionActivityRepository activityRepository;
    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final SandboxExecutionService sandboxExecutionService;

    public SandboxHitlController(
            SandboxExecutionRepository sandboxExecutionRepository,
            HitlRuleRepository hitlRuleRepository,
            TeamMemberRepository teamMemberRepository,
            UserRepository userRepository,
            SandboxExecutionActivityRepository activityRepository,
            ExecutionActivityPersistenceService activityPersistenceService,
            ApplicationEventPublisher eventPublisher,
            SandboxExecutionService sandboxExecutionService) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.hitlRuleRepository = hitlRuleRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
        this.activityPersistenceService = activityPersistenceService;
        this.eventPublisher = eventPublisher;
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @PostMapping("/resolve")
    @Operation(
            summary = "Resolve a pending HITL request",
            description =
                    "Resolves approvals with approved/declined/cancelled (+ optionId) and questions with answered/declined/cancelled (+ content). "
                            + "Suggestion questions (harness suggestion tool calls) steer the agent per their form: content.direction (an alternative user direction) "
                            + "wins over content.followUp (the chosen suggested prompt); an empty answer or declined/cancelled dismisses the suggestion. "
                            + "An optional feedback note is delivered to the agent as steering guidance after the resolution; it replaces the dismissal steer when present.")
    @Transactional
    public ResponseEntity<Void> resolveHitl(@Valid @RequestBody ResolveHitlRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        SandboxExecution execution = findAndVerifyMembership(request.executionId(), userId);
        UUID teamId = execution.getEnvironment().getTeam().getId();

        SandboxExecutionActivity activity = activityRepository
                .findByExecutionIdAndActionId(request.executionId(), request.actionId())
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No HITL request exists for this action"));
        if (activity.getStatus() != ActivityStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This HITL request has expired or was resolved elsewhere");
        }
        ActivityDetail detail = activityPersistenceService.detailOf(activity);
        ActivityHitl hitl = detail != null ? detail.hitl() : null;
        if (hitl == null || hitl.kind() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "HITL request has no resolvable state");
        }

        HitlResponse response = request.response();
        User resolvingUser = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        HitlKind kind = hitl.kind();
        if (kind == HitlKind.APPROVAL) {
            validateApprovalResponse(response, request.optionId());
            validateKnownOption(hitl, request.optionId());
            persistRememberedRules(request, response, execution, teamId, resolvingUser);
        } else {
            validateQuestionResponse(response);
            if (isSuggestionForm(hitl.form())) {
                validateQuestionContent(request.content());
            }
            if (request.rules() != null && !request.rules().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rules are only valid for approvals");
            }
        }

        eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                new HitlResolution(
                        request.executionId(),
                        request.actionId(),
                        kind,
                        response,
                        request.optionId(),
                        request.content(),
                        HitlResolver.user(userId, resolvingUser.getDisplayName()))));

        dispatchFeedbackSteering(request, execution, kind, hitl);

        logger.info(
                "Resolved HITL '{}' (kind={}, response={}) for execution {} by user {}",
                request.actionId(),
                kind,
                response,
                request.executionId(),
                userId);
        return ResponseEntity.noContent().build();
    }

    private void dispatchFeedbackSteering(
            ResolveHitlRequest request, SandboxExecution execution, HitlKind kind, ActivityHitl hitl) {
        if (kind == HitlKind.APPROVAL) {
            if (request.feedback() != null) {
                steeringAfterCommit(execution, approvalFeedbackPrompt(request.feedback()));
            }
            return;
        }
        if (!isSuggestionForm(hitl.form())) {
            // Generic elicitation: only the feedback note steers, as before.
            if (request.feedback() != null) {
                steeringAfterCommit(execution, questionFeedbackPrompt(request.feedback()));
            }
            return;
        }
        String target = suggestionSteeringTarget(request.content());
        if (target != null) {
            steeringAfterCommit(execution, target);
            return;
        }
        if (request.feedback() != null) {
            steeringAfterCommit(execution, questionFeedbackPrompt(request.feedback()));
            return;
        }
        steeringAfterCommit(execution, DISMISSED_SUGGESTION_NOTICE);
    }

    /** Resolves the suggestion answer into the text to dispatch: an explicit alternative direction wins over the suggested prompt; null when neither was provided. */
    static String suggestionSteeringTarget(Map<String, Object> content) {
        if (content == null) {
            return null;
        }
        String direction = contentText(content, "direction");
        if (direction != null) {
            return "Instead of the agent's suggestion, the user directs: " + direction;
        }
        String followUp = contentText(content, "followUp");
        if (followUp != null) {
            return "The user applied the agent's suggested follow-up. Address exactly this: " + followUp;
        }
        return null;
    }

    /** Marker key the sidecar embeds in its own question form; generic elicitations that merely define followUp/direction keys must not match. */
    private static final String SUGGESTION_FORM_MARKER = "x-kratis-suggestion";

    static boolean isSuggestionForm(Map<String, Object> form) {
        return form != null && Boolean.TRUE.equals(form.get(SUGGESTION_FORM_MARKER));
    }

    private static String contentText(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static final int MAX_QUESTION_ANSWER_TEXT = 4000;

    /** Bounded steering payloads: only the suggestion contract keys, sparing generic schema answers. */
    private static void validateQuestionContent(Map<String, Object> content) {
        if (content == null) {
            return;
        }
        for (String key : List.of("followUp", "direction")) {
            Object value = content.get(key);
            if (value instanceof String text && text.length() > MAX_QUESTION_ANSWER_TEXT) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Question answer '" + key + "' exceeds " + MAX_QUESTION_ANSWER_TEXT + " characters");
            }
        }
    }

    static String approvalFeedbackPrompt(String feedback) {
        return "The user attached this guidance while responding to the approval request: " + feedback.trim();
    }

    static String questionFeedbackPrompt(String feedback) {
        return "The user attached this guidance while responding to the agent's question: " + feedback.trim();
    }

    private void steeringAfterCommit(SandboxExecution execution, String prompt) {
        UUID executionId = execution.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sandboxExecutionService.dispatchSystemSteering(executionId, prompt);
            }
        });
    }

    private void persistRememberedRules(
            ResolveHitlRequest request,
            HitlResponse response,
            SandboxExecution execution,
            UUID teamId,
            User resolvingUser) {
        List<CreateHitlRuleRequest> choices = request.rules();
        if (choices == null || choices.isEmpty()) {
            return;
        }
        if (response != HitlResponse.APPROVED && response != HitlResponse.DECLINED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "rules can only be remembered when approving or declining");
        }
        boolean created = false;
        for (CreateHitlRuleRequest choice : choices) {
            if (choice == null) {
                continue;
            }
            boolean exists = hitlRuleRepository.existsByTeamIdAndCommandRootAndRuleTypeAndAction(
                    teamId, choice.commandRoot(), choice.ruleType(), choice.action());
            if (exists) {
                continue;
            }
            HitlRule rule = new HitlRule();
            rule.setTeam(execution.getEnvironment().getTeam());
            rule.setRuleType(choice.ruleType());
            rule.setAction(choice.action());
            rule.setCommandRoot(choice.commandRoot());
            rule.setCreatedBy(resolvingUser);
            rule.setCreatedAt(Instant.now());
            hitlRuleRepository.save(rule);
            created = true;
            logger.info(
                    "Saved remembered {} rule for command root '{}' (team {})",
                    choice.action(),
                    choice.commandRoot(),
                    teamId);
        }
        if (created) {
            eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.PERMISSIONS));
        }
    }

    private static void validateApprovalResponse(HitlResponse response, String optionId) {
        if (response == HitlResponse.APPROVED && (optionId == null || optionId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "optionId is required when approved");
        }
        if (response == HitlResponse.ANSWERED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answered is not valid for approval");
        }
    }

    private static void validateQuestionResponse(HitlResponse response) {
        if (response == HitlResponse.APPROVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "approved is not valid for question");
        }
    }

    private void validateKnownOption(ActivityHitl hitl, String optionId) {
        if (optionId == null || optionId.isBlank()) {
            return;
        }
        List<PermissionOption> options = hitl.options();
        if (options == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HITL request carried no options");
        }
        boolean known = options.stream().anyMatch(option -> option != null && optionId.equals(option.optionId()));
        if (!known) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown optionId: " + optionId);
        }
    }

    private SandboxExecution findAndVerifyMembership(UUID executionId, UUID userId) {
        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));
        UUID teamId = execution.getEnvironment().getTeam().getId();
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
        return execution;
    }
}
