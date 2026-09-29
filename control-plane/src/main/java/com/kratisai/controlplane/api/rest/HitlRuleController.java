package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.HitlRuleDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.HitlRuleService;
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
@RequestMapping("/api/v1/teams/{teamId}/hitl-rules")
@Tag(name = "HITL Rules", description = "Team HITL rules: always allow or always block command roots")
@SecurityRequirement(name = "bearerAuth")
public class HitlRuleController {

    private final HitlRuleService hitlRuleService;

    public HitlRuleController(HitlRuleService hitlRuleService) {
        this.hitlRuleService = hitlRuleService;
    }

    @GetMapping
    @Operation(summary = "List team HITL rules", description = "Lists all HITL rules for a team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "List of HITL rules",
                content = @Content(schema = @Schema(implementation = HitlRuleDto.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content)
    })
    public ResponseEntity<List<HitlRuleDto>> listRules(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        List<HitlRuleDto> rules = hitlRuleService.listRules(userId, teamId);
        return ResponseEntity.ok(rules);
    }

    @PostMapping
    @Operation(summary = "Create a HITL rule", description = "Creates a new ALLOW or DENY HITL rule for a team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Rule created",
                content = @Content(schema = @Schema(implementation = HitlRuleDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "409", description = "Rule already exists", content = @Content)
    })
    public ResponseEntity<HitlRuleDto> createRule(
            @PathVariable UUID teamId, @Valid @RequestBody CreateHitlRuleRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        HitlRuleDto created = hitlRuleService.createRule(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{ruleId}")
    @Operation(summary = "Delete a HITL rule", description = "Deletes an existing HITL rule")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Rule deleted"),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "404", description = "Rule not found", content = @Content)
    })
    public ResponseEntity<Void> deleteRule(@PathVariable UUID teamId, @PathVariable UUID ruleId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        hitlRuleService.deleteRule(userId, teamId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
