package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.DiffFileDto;
import com.kratisai.controlplane.api.restdto.DiffSummaryDto;
import com.kratisai.controlplane.api.restdto.ReadFileSliceDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.SandboxExecutionDiffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chats/{chatId}/executions/{executionId}/diff")
@Tag(name = "Execution Diff", description = "Endpoints for inspecting working tree diffs and file slices")
public class SandboxExecutionDiffController {

    private final SandboxExecutionDiffService diffService;

    public SandboxExecutionDiffController(SandboxExecutionDiffService diffService) {
        this.diffService = diffService;
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Get execution diff summary",
            description =
                    "Returns a summary manifest of all modified, added, and deleted files in the execution workspace, diffed against the execution's target branch")
    public ResponseEntity<DiffSummaryDto> getDiffSummary(@PathVariable UUID chatId, @PathVariable UUID executionId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(diffService.getDiffSummary(userId, chatId, executionId));
    }

    @GetMapping("/file")
    @Operation(
            summary = "Get single file diff",
            description = "Returns a unified diff patch for a single file in the execution workspace")
    public ResponseEntity<DiffFileDto> getFileDiff(
            @PathVariable UUID chatId, @PathVariable UUID executionId, @RequestParam String path) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(diffService.getFileDiff(userId, chatId, executionId, path));
    }

    @GetMapping("/context")
    @Operation(
            summary = "Read file line slice",
            description = "Returns a line slice from a workspace file for hunk context expansion")
    public ResponseEntity<ReadFileSliceDto> getReadFileSlice(
            @PathVariable UUID chatId,
            @PathVariable UUID executionId,
            @RequestParam String path,
            @RequestParam(defaultValue = "1") int startLine,
            @RequestParam(defaultValue = "100") int endLine) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(diffService.getReadFileSlice(userId, chatId, executionId, path, startLine, endLine));
    }

    @GetMapping("/export")
    @Operation(
            summary = "Export unified diff patch",
            description = "Returns the combined unified diff patch for all changed files in the execution workspace")
    public ResponseEntity<String> exportPatch(@PathVariable UUID chatId, @PathVariable UUID executionId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        String patch = diffService.exportPatch(userId, chatId, executionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"execution-" + executionId + ".patch\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(patch);
    }
}
