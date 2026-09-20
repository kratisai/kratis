package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.ResolveHitlRequest;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.model.HitlRule;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.HitlRuleRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.PendingHitlRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final HitlRuleRepository hitlRuleRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public SandboxHitlController(
            SandboxExecutionRepository sandboxExecutionRepository,
            HitlRuleRepository hitlRuleRepository,
            TeamMemberRepository teamMemberRepository,
            UserRepository userRepository,
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.hitlRuleRepository = hitlRuleRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
        this.pendingHitlRegistry = pendingHitlRegistry;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/resolve")
    @Operation(
            summary = "Resolve a pending HITL request",
            description =
                    "Resolves approvals with approved/declined/cancelled (+ optionId) and questions with answered/declined/cancelled (+ content).")
    @Transactional
    public ResponseEntity<Void> resolveHitl(@Valid @RequestBody ResolveHitlRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        SandboxExecution execution = findAndVerifyMembership(request.executionId(), userId);
        UUID teamId = execution.getEnvironment().getTeam().getId();

        PendingHitlRegistry.PendingHitl pending =
                pendingHitlRegistry.getPending().get(request.executionId());
        if (pending == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No pending HITL request for this execution");
        }
        if (!pending.request().hitlId().equals(request.hitlId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hitlId does not match pending request");
        }

        HitlResponse response = request.response();
        User resolvingUser = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        HitlKind kind = pending.request().kind();
        if (kind == HitlKind.APPROVAL) {
            validateApprovalResponse(response, request.optionId());
            validateKnownOption(pending, request.optionId());
            persistRememberedRules(request, response, execution, teamId, resolvingUser);
        } else {
            validateQuestionResponse(response);
            if (request.rules() != null && !request.rules().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rules are only valid for approvals");
            }
        }

        eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                new ExecutionHitlResolvedResult(
                        request.executionId(),
                        request.hitlId(),
                        kind,
                        response,
                        request.optionId(),
                        request.content(),
                        userId,
                        resolvingUser.getDisplayName())));

        logger.info(
                "Resolved HITL '{}' (kind={}, response={}) for execution {} by user {}",
                request.hitlId(),
                kind,
                response,
                request.executionId(),
                userId);
        return ResponseEntity.noContent().build();
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

    private void validateKnownOption(PendingHitlRegistry.PendingHitl pending, String optionId) {
        if (optionId == null || optionId.isBlank()) {
            return;
        }
        List<PermissionOption> options = pending.request().options();
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
