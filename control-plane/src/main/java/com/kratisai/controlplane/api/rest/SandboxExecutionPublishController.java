package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.PublishCapabilitiesDto;
import com.kratisai.controlplane.api.restdto.PublishPrRequestDto;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.PushBranchRequestDto;
import com.kratisai.controlplane.api.restdto.PushBranchResponseDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.SandboxExecutionPublishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chats/{chatId}/executions/{executionId}")
@Tag(name = "Execution Publish", description = "Endpoints for publish capabilities, git push, and PR publishing")
public class SandboxExecutionPublishController {

    private final SandboxExecutionPublishService publishService;

    public SandboxExecutionPublishController(SandboxExecutionPublishService publishService) {
        this.publishService = publishService;
    }

    @GetMapping("/publish-capabilities")
    @Operation(
            summary = "Get publish capabilities",
            description = "Returns repository provider capabilities, git stats, and suggested PR/commit messages")
    public ResponseEntity<PublishCapabilitiesDto> getPublishCapabilities(
            @PathVariable UUID chatId, @PathVariable UUID executionId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(publishService.getPublishCapabilities(userId, chatId, executionId));
    }

    @PostMapping("/push-branch")
    @Operation(
            summary = "Push working tree to remote branch",
            description = "Stages, commits, and pushes sandbox execution changes to a remote git branch")
    public ResponseEntity<PushBranchResponseDto> pushBranch(
            @PathVariable UUID chatId, @PathVariable UUID executionId, @RequestBody PushBranchRequestDto request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(publishService.pushBranch(userId, chatId, executionId, request));
    }

    @PostMapping("/publish-pr")
    @Operation(
            summary = "Publish upstream Pull Request or Merge Request",
            description = "Pushes changes to remote origin and opens a Pull Request or Merge Request on the provider")
    public ResponseEntity<PullRequestResultDto> publishPullRequest(
            @PathVariable UUID chatId, @PathVariable UUID executionId, @RequestBody PublishPrRequestDto request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(publishService.publishPullRequest(userId, chatId, executionId, request));
    }
}
