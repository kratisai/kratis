package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.ResolveHitlRequest;
import com.kratisai.controlplane.api.wsdto.ApprovalOptionKind;
import com.kratisai.controlplane.api.wsdto.HitlKind;
import com.kratisai.controlplane.api.wsdto.HitlResponse;
import com.kratisai.controlplane.api.wsdto.PermissionOption;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxPermissionAction;
import com.kratisai.controlplane.model.SandboxPermissionRule;
import com.kratisai.controlplane.model.SandboxPermissionRuleType;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.SandboxExecutionHitlResolvedEvent;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.SandboxPermissionRuleRepository;
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
    private final SandboxPermissionRuleRepository sandboxPermissionRuleRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final PendingHitlRegistry pendingHitlRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public SandboxHitlController(
            SandboxExecutionRepository sandboxExecutionRepository,
            SandboxPermissionRuleRepository sandboxPermissionRuleRepository,
            TeamMemberRepository teamMemberRepository,
            UserRepository userRepository,
            PendingHitlRegistry pendingHitlRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.sandboxPermissionRuleRepository = sandboxPermissionRuleRepository;
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
        if (!pending.hitlId().equals(request.hitlId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hitlId does not match pending request");
        }

        HitlResponse response = request.response();
        User resolvingUser = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (pending.kind() == HitlKind.APPROVAL) {
            validateApprovalResponse(response, request.optionId());
            if (response == HitlResponse.APPROVED) {
                ApprovalOptionKind kind = resolveOptionKind(pending, request.optionId());
                if (kind == ApprovalOptionKind.ALLOW_ALWAYS) {
                    String command = pending.command() != null ? pending.command() : "";
                    boolean exists = sandboxPermissionRuleRepository.existsByTeamIdAndCommandRootAndRuleTypeAndAction(
                            teamId, command, SandboxPermissionRuleType.EXACT, SandboxPermissionAction.ALLOW);
                    if (!exists) {
                        SandboxPermissionRule rule = new SandboxPermissionRule();
                        rule.setTeam(execution.getEnvironment().getTeam());
                        rule.setRuleType(SandboxPermissionRuleType.EXACT);
                        rule.setAction(SandboxPermissionAction.ALLOW);
                        rule.setCommandRoot(command);
                        rule.setCreatedBy(resolvingUser);
                        rule.setCreatedAt(Instant.now());
                        sandboxPermissionRuleRepository.save(rule);
                        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.PERMISSIONS));
                        logger.info("Saved persistent allow rule for command '{}' (team {})", command, teamId);
                    }
                }
            }
        } else {
            validateQuestionResponse(response);
        }

        eventPublisher.publishEvent(new SandboxExecutionHitlResolvedEvent(
                teamId,
                request.executionId(),
                request.hitlId(),
                pending.kind(),
                response,
                request.optionId(),
                request.content(),
                userId,
                resolvingUser.getDisplayName()));

        logger.info(
                "Resolved HITL '{}' (kind={}, response={}) for execution {} by user {}",
                request.hitlId(),
                pending.kind(),
                response,
                request.executionId(),
                userId);
        return ResponseEntity.noContent().build();
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

    private ApprovalOptionKind resolveOptionKind(PendingHitlRegistry.PendingHitl pending, String optionId) {
        List<PermissionOption> options = pending.options();
        if (options == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HITL request carried no options");
        }
        for (PermissionOption option : options) {
            if (option != null && optionId.equals(option.optionId())) {
                return option.kind();
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown optionId: " + optionId);
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
