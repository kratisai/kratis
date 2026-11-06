package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.TeamSkillResponse;
import com.kratisai.controlplane.api.restdto.UpdateTeamSkillRequest;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.TeamSkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/skills")
@Tag(name = "Team Skills", description = "Manage team-scoped skills stored as tar.gz archives")
@SecurityRequirement(name = "bearerAuth")
public class TeamSkillController {

    private final TeamSkillService teamSkillService;

    public TeamSkillController(TeamSkillService teamSkillService) {
        this.teamSkillService = teamSkillService;
    }

    @GetMapping
    @Operation(summary = "List team skills", description = "Lists metadata for every skill configured for the team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "List of team skills",
                content = @Content(schema = @Schema(implementation = TeamSkillResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content)
    })
    public ResponseEntity<List<TeamSkillResponse>> listSkills(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(teamSkillService.listSkills(userId, teamId));
    }

    @GetMapping("/{skillId}")
    @Operation(summary = "Get a team skill", description = "Returns a single team skill's metadata")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Team skill",
                content = @Content(schema = @Schema(implementation = TeamSkillResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "404", description = "Skill not found", content = @Content)
    })
    public ResponseEntity<TeamSkillResponse> getSkill(@PathVariable UUID teamId, @PathVariable UUID skillId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(teamSkillService.getSkill(userId, teamId, skillId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload a team skill",
            description = "Uploads a .tar.gz skill archive containing SKILL.md with valid frontmatter")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Skill uploaded",
                content = @Content(schema = @Schema(implementation = TeamSkillResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid archive or frontmatter", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "409", description = "Skill name already exists", content = @Content)
    })
    public ResponseEntity<TeamSkillResponse> uploadSkill(
            @PathVariable UUID teamId, @RequestParam("file") MultipartFile file) {
        UUID userId = SecurityUtil.getCurrentUserId();
        TeamSkillResponse created = teamSkillService.uploadSkill(userId, teamId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{skillId}")
    @Operation(
            summary = "Enable or disable a team skill",
            description =
                    "Enables or disables a team skill. Name and description come from SKILL.md; re-upload to change them.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Skill updated",
                content = @Content(schema = @Schema(implementation = TeamSkillResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "404", description = "Skill not found", content = @Content)
    })
    public ResponseEntity<TeamSkillResponse> updateSkill(
            @PathVariable UUID teamId, @PathVariable UUID skillId, @RequestBody UpdateTeamSkillRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(teamSkillService.updateSkill(userId, teamId, skillId, request));
    }

    @DeleteMapping("/{skillId}")
    @Operation(
            summary = "Delete a team skill",
            description = "Deletes the archive from blob storage and the skill record")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Skill deleted"),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content),
        @ApiResponse(responseCode = "404", description = "Skill not found", content = @Content)
    })
    public ResponseEntity<Void> deleteSkill(@PathVariable UUID teamId, @PathVariable UUID skillId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        teamSkillService.deleteSkill(userId, teamId, skillId);
        return ResponseEntity.noContent().build();
    }
}
