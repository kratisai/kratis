package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.CreateSandboxPermissionRuleRequest;
import com.kratisai.controlplane.api.restdto.SandboxPermissionRuleDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.SandboxPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/permissions")
@Tag(name = "Sandbox Permissions", description = "Sandbox execution permission rule management scoped to teams")
@SecurityRequirement(name = "bearerAuth")
public class SandboxPermissionController {

    private final SandboxPermissionService sandboxPermissionService;

    public SandboxPermissionController(SandboxPermissionService sandboxPermissionService) {
        this.sandboxPermissionService = sandboxPermissionService;
    }

    @GetMapping
    @Operation(
            summary = "List team permission rules",
            description = "Lists all active sandbox permission rules for a team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "List of permission rules",
                content = @Content(schema = @Schema(implementation = SandboxPermissionRuleDto.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content)
    })
    public ResponseEntity<List<SandboxPermissionRuleDto>> listRules(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        List<SandboxPermissionRuleDto> rules = sandboxPermissionService.listRules(userId, teamId);
        return ResponseEntity.ok(rules);
    }

    @PostMapping
    @Operation(
            summary = "Create a sandbox permission rule",
            description = "Creates a new ALLOW or DENY permission rule for a team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Rule created",
                content = @Content(schema = @Schema(implementation = SandboxPermissionRuleDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "409", description = "Rule already exists", content = @Content)
    })
    public ResponseEntity<SandboxPermissionRuleDto> createRule(
            @PathVariable UUID teamId, @Valid @RequestBody CreateSandboxPermissionRuleRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        SandboxPermissionRuleDto created = sandboxPermissionService.createRule(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{ruleId}")
    @Operation(summary = "Delete a permission rule", description = "Deletes an existing sandbox permission rule")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Rule deleted"),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "404", description = "Rule not found", content = @Content)
    })
    public ResponseEntity<Void> deleteRule(@PathVariable UUID teamId, @PathVariable UUID ruleId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        sandboxPermissionService.deleteRule(userId, teamId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
