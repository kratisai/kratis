package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest;
import com.kratisai.controlplane.api.restdto.SandboxExecutionDto;
import com.kratisai.controlplane.api.restdto.SteerExecutionRequest;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.SandboxExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chats")
@Tag(name = "Chat Executions", description = "Endpoints for launching sandbox executions within a chat")
public class ChatExecutionController {

    private final SandboxExecutionService sandboxExecutionService;

    public ChatExecutionController(SandboxExecutionService sandboxExecutionService) {
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @GetMapping("/{chatId}/executions")
    @Operation(
            summary = "List chat executions",
            description = "Lists all sandbox executions for a chat, ordered by start time")
    public ResponseEntity<List<SandboxExecutionDto>> listExecutions(@PathVariable UUID chatId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(sandboxExecutionService.listExecutions(userId, chatId));
    }

    @PostMapping("/{chatId}/executions")
    @Operation(
            summary = "Launch chat execution",
            description = "Launches a new sandbox or connector execution within a chat")
    public ResponseEntity<SandboxExecutionDto> launchExecution(
            @PathVariable UUID chatId, @Valid @RequestBody CreateSandboxExecutionRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        SandboxExecutionDto dto = sandboxExecutionService.createExecution(userId, chatId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PostMapping("/{chatId}/executions/{executionId}/steer")
    @Operation(
            summary = "Steer running execution",
            description = "Provides mid-execution steering instructions or diff review comments to the running agent")
    public ResponseEntity<Void> steerExecution(
            @PathVariable UUID chatId,
            @PathVariable UUID executionId,
            @Valid @RequestBody SteerExecutionRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        sandboxExecutionService.steerExecution(userId, chatId, executionId, request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{chatId}/executions/{executionId}/terminate")
    @Operation(
            summary = "Terminate execution",
            description = "Gracefully terminates a running execution by sending env.terminate to the connector")
    public ResponseEntity<Void> terminateExecution(@PathVariable UUID chatId, @PathVariable UUID executionId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        sandboxExecutionService.terminateExecution(userId, chatId, executionId);
        return ResponseEntity.accepted().build();
    }
}
