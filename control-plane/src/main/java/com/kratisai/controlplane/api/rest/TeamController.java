package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.model.AgentHarness;
import com.kratisai.controlplane.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "Teams", description = "Team management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    @Operation(summary = "Create a new team", description = "Creates a team and assigns the current user as owner")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Team created",
                        content = @Content(schema = @Schema(implementation = TeamDto.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content)
            })
    public ResponseEntity<TeamDto> createTeam(@Valid @RequestBody CreateTeamRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        TeamDto team = teamService.createTeam(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(team);
    }

    @GetMapping
    @Operation(
            summary = "List teams for current user",
            description = "Returns all teams the current user is a member of")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of teams",
                        content = @Content(schema = @Schema(implementation = TeamDto.class)))
            })
    public ResponseEntity<List<TeamDto>> listTeams() {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(teamService.listTeamsForUser(userId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get team details", description = "Returns detailed team information including members")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Team details",
                        content = @Content(schema = @Schema(implementation = TeamDetailDto.class))),
                @ApiResponse(responseCode = "404", description = "Team not found", content = @Content),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<TeamDetailDto> getTeam(@PathVariable UUID id) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(teamService.getTeam(userId, id));
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update a team",
            description = "Updates team name and/or description. Requires owner or admin role.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Team updated",
                        content = @Content(schema = @Schema(implementation = TeamDto.class))),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(responseCode = "404", description = "Team not found", content = @Content)
            })
    public ResponseEntity<TeamDto> updateTeam(@PathVariable UUID id, @Valid @RequestBody UpdateTeamRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(teamService.updateTeam(userId, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a team", description = "Deletes a team and all its members. Requires owner role.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Team deleted"),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(responseCode = "404", description = "Team not found", content = @Content)
            })
    public ResponseEntity<Void> deleteTeam(@PathVariable UUID id) {
        UUID userId = SecurityUtil.getCurrentUserId();
        teamService.deleteTeam(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add a team member", description = "Adds a user to the team. Requires owner or admin role.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Member added",
                        content = @Content(schema = @Schema(implementation = TeamMemberDto.class))),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(responseCode = "404", description = "User or team not found", content = @Content),
                @ApiResponse(responseCode = "409", description = "User is already a member", content = @Content)
            })
    public ResponseEntity<TeamMemberDto> addMember(
            @PathVariable UUID id, @Valid @RequestBody AddTeamMemberRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(teamService.addMember(userId, id, request));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Operation(
            summary = "Remove a team member",
            description = "Removes a user from the team. Requires owner or admin role.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Member removed"),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content)
            })
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        UUID currentUserId = SecurityUtil.getCurrentUserId();
        teamService.removeMember(currentUserId, id, userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/tavily-api-key")
    @Operation(
            summary = "Update Tavily API key",
            description = "Updates the Tavily API key for the team. Requires owner or admin role.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Tavily API key updated"),
                @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
                @ApiResponse(responseCode = "404", description = "Team not found", content = @Content)
            })
    public ResponseEntity<Void> updateTavilyApiKey(@PathVariable UUID id, @RequestBody TavilyApiKeyRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        teamService.updateTavilyApiKey(userId, id, request.tavilyApiKey());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/harnesses")
    @Operation(
            summary = "List available agent harnesses",
            description = "Returns the list of available agent harnesses for the team")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of harnesses",
                        content = @Content(schema = @Schema(implementation = AgentHarnessDto.class)))
            })
    public ResponseEntity<List<AgentHarnessDto>> listHarnesses(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        teamService.validateMembership(userId, teamId);

        List<AgentHarnessDto> harnesses = Arrays.stream(AgentHarness.values())
                .map(h -> new AgentHarnessDto(h.name(), h.getName()))
                .toList();
        return ResponseEntity.ok(harnesses);
    }
}
