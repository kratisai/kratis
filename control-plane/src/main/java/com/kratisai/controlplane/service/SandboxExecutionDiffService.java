package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.DiffFileDto;
import com.kratisai.controlplane.api.restdto.DiffSummaryDto;
import com.kratisai.controlplane.api.restdto.ReadFileSliceDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SandboxExecutionDiffService {

    private static final long DIFF_TIMEOUT_SECONDS = 30;

    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final ChatRepository chatRepository;
    private final EnvironmentRpcClient environmentRpcClient;

    public SandboxExecutionDiffService(
            SandboxExecutionRepository sandboxExecutionRepository,
            ChatRepository chatRepository,
            EnvironmentRpcClient environmentRpcClient) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.chatRepository = chatRepository;
        this.environmentRpcClient = environmentRpcClient;
    }

    @Transactional(readOnly = true)
    public DiffSummaryDto getDiffSummary(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        String targetBranch = resolveTargetBranch(execution);

        try {
            EnvironmentConnectorResult.GitDiffSummary result = environmentRpcClient.request(
                    env.getId(),
                    new EnvironmentRpcPayload.GitDiffSummary(targetBranch, executionId.toString()),
                    DIFF_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);

            if (result == null) {
                return new DiffSummaryDto("", "", 0, 0, List.of());
            }

            List<DiffSummaryDto.DiffSummaryFileDto> files = result.files().stream()
                    .map(f -> new DiffSummaryDto.DiffSummaryFileDto(
                            f.path(), f.status(), f.additions(), f.deletions(), f.isCollapsedByDefault()))
                    .toList();

            return new DiffSummaryDto(
                    result.baseCommit(), result.headCommit(), result.totalAdditions(), result.totalDeletions(), files);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Diff summary request interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "Timed out fetching diff summary from sandbox", e);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to retrieve diff summary: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public DiffFileDto getFileDiff(UUID userId, UUID chatId, UUID executionId, String path) {
        Objects.requireNonNull(path, "path is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        String targetBranch = resolveTargetBranch(execution);

        try {
            EnvironmentConnectorResult.GitFileDiff result = environmentRpcClient.request(
                    env.getId(),
                    new EnvironmentRpcPayload.GitFileDiff(path, targetBranch, executionId.toString()),
                    DIFF_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);

            if (result == null) {
                return new DiffFileDto(path, "", 0, 0, 0);
            }

            return new DiffFileDto(
                    result.path(), result.patch(), result.additions(), result.deletions(), result.totalLines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "File diff request interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "Timed out fetching file diff from sandbox", e);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to retrieve file diff: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public ReadFileSliceDto getReadFileSlice(
            UUID userId, UUID chatId, UUID executionId, String path, int startLine, int endLine) {
        Objects.requireNonNull(path, "path is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        try {
            EnvironmentConnectorResult.ReadFileSlice result = environmentRpcClient.request(
                    env.getId(),
                    new EnvironmentRpcPayload.ReadFileSlice(path, startLine, endLine, executionId.toString()),
                    DIFF_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);

            if (result == null) {
                return new ReadFileSliceDto(path, startLine, List.of());
            }

            return new ReadFileSliceDto(result.path(), result.startLine(), result.lines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "File slice request interrupted", e);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "Timed out fetching file slice from sandbox", e);
        } catch (EnvironmentRpcClient.EnvironmentRpcException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to read file slice: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public String exportPatch(UUID userId, UUID chatId, UUID executionId) {
        DiffSummaryDto summary = getDiffSummary(userId, chatId, executionId);
        StringBuilder patchBuilder = new StringBuilder();

        for (DiffSummaryDto.DiffSummaryFileDto file : summary.files()) {
            DiffFileDto fileDiff = getFileDiff(userId, chatId, executionId, file.path());
            if (fileDiff.patch() != null && !fileDiff.patch().isBlank()) {
                patchBuilder
                        .append("diff --git a/")
                        .append(file.path())
                        .append(" b/")
                        .append(file.path())
                        .append("\n");
                patchBuilder.append(fileDiff.patch()).append("\n");
            }
        }
        return patchBuilder.toString();
    }

    private String resolveTargetBranch(SandboxExecution execution) {
        if (execution.getTargetBranch() != null && !execution.getTargetBranch().isBlank()) {
            return execution.getTargetBranch();
        }
        Repository repo = execution.getRepository();
        if (repo != null && repo.getBranch() != null && !repo.getBranch().isBlank()) {
            return repo.getBranch();
        }
        return "main"; // new Repo.
    }

    private SandboxExecution validateAndGetExecution(UUID userId, UUID chatId, UUID executionId) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        if (!chat.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to chat");
        }

        SandboxExecution execution = sandboxExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));

        if (!execution.getChat().getId().equals(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution does not belong to chat");
        }

        return execution;
    }
}
