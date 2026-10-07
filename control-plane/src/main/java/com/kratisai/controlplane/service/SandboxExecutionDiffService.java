package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.DiffFileDto;
import com.kratisai.controlplane.api.restdto.DiffSummaryDto;
import com.kratisai.controlplane.api.restdto.ReadFileSliceDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final ExecutionDiffSnapshotRepository diffSnapshotRepository;
    private final BlobStorageService blobStorageService;
    private final ObjectMapper objectMapper;

    public SandboxExecutionDiffService(
            SandboxExecutionRepository sandboxExecutionRepository,
            ChatRepository chatRepository,
            EnvironmentRpcClient environmentRpcClient,
            ExecutionDiffSnapshotRepository diffSnapshotRepository,
            BlobStorageService blobStorageService,
            ObjectMapper objectMapper) {
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.chatRepository = chatRepository;
        this.environmentRpcClient = environmentRpcClient;
        this.diffSnapshotRepository = diffSnapshotRepository;
        this.blobStorageService = blobStorageService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DiffSummaryDto getDiffSummary(UUID userId, UUID chatId, UUID executionId) {
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null || env.getStatus() != EnvironmentStatus.CONNECTED) {
            return getDiffSummaryFromSnapshot(executionId);
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
        } catch (TimeoutException | EnvironmentRpcClient.EnvironmentRpcException e) {
            return getDiffSummaryFromSnapshot(executionId);
        }
    }

    @Transactional(readOnly = true)
    public DiffFileDto getFileDiff(UUID userId, UUID chatId, UUID executionId, String path) {
        Objects.requireNonNull(path, "path is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        ExecutionEnvironment env = execution.getEnvironment();
        if (env == null || env.getStatus() != EnvironmentStatus.CONNECTED) {
            return getFileDiffFromSnapshot(executionId, path);
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
        } catch (TimeoutException | EnvironmentRpcClient.EnvironmentRpcException e) {
            return getFileDiffFromSnapshot(executionId, path);
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
        validateAndGetExecution(userId, chatId, executionId);
        String patchPath = "diffs/" + executionId + ".patch";
        if (blobStorageService.exists(patchPath)) {
            try (InputStream is = blobStorageService.getObject(patchPath)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fall back to live concatenation
            }
        }

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

    private DiffSummaryDto getDiffSummaryFromSnapshot(UUID executionId) {
        return diffSnapshotRepository
                .findByExecutionId(executionId)
                .map(snapshot -> {
                    try {
                        List<EnvironmentConnectorResult.GitDiffSummaryFile> files = objectMapper.readValue(
                                snapshot.getSummaryJson(),
                                new TypeReference<List<EnvironmentConnectorResult.GitDiffSummaryFile>>() {});
                        List<DiffSummaryDto.DiffSummaryFileDto> fileDtos = files.stream()
                                .map(f -> new DiffSummaryDto.DiffSummaryFileDto(
                                        f.path(), f.status(), f.additions(), f.deletions(), f.isCollapsedByDefault()))
                                .toList();
                        return new DiffSummaryDto(
                                snapshot.getBaseCommit() != null ? snapshot.getBaseCommit() : "",
                                snapshot.getHeadCommit() != null ? snapshot.getHeadCommit() : "",
                                snapshot.getTotalAdditions(),
                                snapshot.getTotalDeletions(),
                                fileDtos);
                    } catch (Exception e) {
                        return new DiffSummaryDto(
                                snapshot.getBaseCommit() != null ? snapshot.getBaseCommit() : "",
                                snapshot.getHeadCommit() != null ? snapshot.getHeadCommit() : "",
                                snapshot.getTotalAdditions(),
                                snapshot.getTotalDeletions(),
                                List.of());
                    }
                })
                .orElse(new DiffSummaryDto("", "", 0, 0, List.of()));
    }

    private DiffFileDto getFileDiffFromSnapshot(UUID executionId, String path) {
        String patchStoragePath = "diffs/" + executionId + ".patch";
        if (!blobStorageService.exists(patchStoragePath)) {
            return new DiffFileDto(path, "", 0, 0, 0);
        }
        try (InputStream is = blobStorageService.getObject(patchStoragePath)) {
            String fullPatch = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return extractFileDiff(path, fullPatch);
        } catch (IOException e) {
            return new DiffFileDto(path, "", 0, 0, 0);
        }
    }

    static DiffFileDto extractFileDiff(String path, String fullPatch) {
        if (fullPatch == null || fullPatch.isBlank()) {
            return new DiffFileDto(path, "", 0, 0, 0);
        }

        String[] lines = fullPatch.split("\\r?\\n");
        StringBuilder patchBuilder = new StringBuilder();
        boolean inTargetFile = false;
        int additions = 0;
        int deletions = 0;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (inTargetFile) {
                    break;
                }
                if (line.endsWith(" b/" + path)
                        || line.contains(" b/" + path + " ")
                        || line.equals("diff --git a/" + path + " b/" + path)) {
                    inTargetFile = true;
                    patchBuilder.append(line).append("\n");
                    continue;
                }
            } else if (inTargetFile) {
                patchBuilder.append(line).append("\n");
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    additions++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletions++;
                }
            }
        }

        if (!inTargetFile) {
            return new DiffFileDto(path, "", 0, 0, 0);
        }

        return new DiffFileDto(path, patchBuilder.toString(), additions, deletions, additions + deletions);
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
