package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.DiffFileDto;
import com.kratisai.controlplane.api.restdto.DiffSummaryDto;
import com.kratisai.controlplane.api.restdto.ReadFileSliceDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
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

/**
 * Serves every diff review read from the control-plane copy.
 */
@Service
public class SandboxExecutionDiffService {

    private static final long SLICE_TIMEOUT_SECONDS = 30;

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
        validateAndGetExecution(userId, chatId, executionId);
        return getDiffSummaryFromSnapshot(executionId);
    }

    @Transactional(readOnly = true)
    public DiffFileDto getFileDiff(UUID userId, UUID chatId, UUID executionId, String path) {
        Objects.requireNonNull(path, "path is required");
        validateAndGetExecution(userId, chatId, executionId);
        return getFileDiffFromSnapshot(executionId, path);
    }

    /** Live-only: reads the current workspace file content from the sandbox for hunk expansion. */
    @Transactional(readOnly = true)
    public ReadFileSliceDto getReadFileSlice(
            UUID userId, UUID chatId, UUID executionId, String path, int startLine, int endLine) {
        Objects.requireNonNull(path, "path is required");
        SandboxExecution execution = validateAndGetExecution(userId, chatId, executionId);
        var env = execution.getEnvironment();
        if (env == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution has no associated environment");
        }

        try {
            EnvironmentConnectorResult.ReadFileSlice result = environmentRpcClient.request(
                    env.getId(),
                    new EnvironmentRpcPayload.ReadFileSlice(path, startLine, endLine, executionId.toString()),
                    SLICE_TIMEOUT_SECONDS,
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
        StringBuilder fullPatch = new StringBuilder();
        for (EnvironmentConnectorResult.GitDiffManifestFile file : manifestFiles(executionId)) {
            fullPatch.append(readSection(executionId, file));
        }
        return fullPatch.toString();
    }

    private DiffSummaryDto getDiffSummaryFromSnapshot(UUID executionId) {
        return diffSnapshotRepository
                .findByExecutionId(executionId)
                .map(snapshot -> new DiffSummaryDto(
                        snapshot.getBaseCommit() != null ? snapshot.getBaseCommit() : "",
                        snapshot.getHeadCommit() != null ? snapshot.getHeadCommit() : "",
                        snapshot.getTotalAdditions(),
                        snapshot.getTotalDeletions(),
                        readManifest(snapshot).stream()
                                .map(f -> new DiffSummaryDto.DiffSummaryFileDto(
                                        f.path(), f.status(), f.additions(), f.deletions(), f.isCollapsedByDefault()))
                                .toList()))
                .orElse(new DiffSummaryDto("", "", 0, 0, List.of()));
    }

    private DiffFileDto getFileDiffFromSnapshot(UUID executionId, String path) {
        return manifestFiles(executionId).stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .map(file -> new DiffFileDto(
                        path,
                        readSection(executionId, file),
                        file.additions(),
                        file.deletions(),
                        file.additions() + file.deletions()))
                .orElse(new DiffFileDto(path, "", 0, 0, 0));
    }

    private List<EnvironmentConnectorResult.GitDiffManifestFile> manifestFiles(UUID executionId) {
        return diffSnapshotRepository
                .findByExecutionId(executionId)
                .map(this::readManifest)
                .orElse(List.of());
    }

    /** The snapshot's JSON column is the committed manifest; a malformed value is a server fault. */
    private List<EnvironmentConnectorResult.GitDiffManifestFile> readManifest(ExecutionDiffSnapshot snapshot) {
        try {
            return objectMapper.readValue(
                    snapshot.getSummaryJson(),
                    new TypeReference<List<EnvironmentConnectorResult.GitDiffManifestFile>>() {});
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Stored diff manifest is unreadable", e);
        }
    }

    private String readSection(UUID executionId, EnvironmentConnectorResult.GitDiffManifestFile file) {
        byte[] bytes = blobStorageService.getObjectBytes("diffs/" + executionId + "/" + file.sha());
        if (bytes == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Stored diff section is missing: " + file.path());
        }
        return new String(bytes, StandardCharsets.UTF_8);
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
