package com.kratisai.controlplane.service;

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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        Optional<ExecutionDiffSnapshot> snapshotOpt = diffSnapshotRepository.findByExecutionId(executionId);
        if (snapshotOpt.isEmpty()) {
            return "";
        }
        ExecutionDiffSnapshot snapshot = snapshotOpt.get();
        if (snapshot.getManifestDigest() != null) {
            try {
                List<EnvironmentConnectorResult.GitDiffManifestFile> files = objectMapper.readValue(
                        snapshot.getSummaryJson(),
                        new TypeReference<List<EnvironmentConnectorResult.GitDiffManifestFile>>() {});
                StringBuilder fullPatch = new StringBuilder();
                for (EnvironmentConnectorResult.GitDiffManifestFile file : files) {
                    String blobPath = "diffs/" + executionId + "/" + file.sha();
                    if (blobStorageService.exists(blobPath)) {
                        try (InputStream is = blobStorageService.getObject(blobPath)) {
                            fullPatch.append(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
                return fullPatch.toString();
            } catch (Exception e) {
                // fall back to readFullPatch
            }
        }
        return readFullPatch(executionId).orElse("");
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
        Optional<ExecutionDiffSnapshot> snapshotOpt = diffSnapshotRepository.findByExecutionId(executionId);
        if (snapshotOpt.isPresent() && snapshotOpt.get().getManifestDigest() != null) {
            try {
                List<EnvironmentConnectorResult.GitDiffManifestFile> files = objectMapper.readValue(
                        snapshotOpt.get().getSummaryJson(),
                        new TypeReference<List<EnvironmentConnectorResult.GitDiffManifestFile>>() {});
                for (EnvironmentConnectorResult.GitDiffManifestFile file : files) {
                    if (file.path().equals(path)) {
                        String blobPath = "diffs/" + executionId + "/" + file.sha();
                        if (blobStorageService.exists(blobPath)) {
                            try (InputStream is = blobStorageService.getObject(blobPath)) {
                                String sectionPatch = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                                return new DiffFileDto(
                                        path,
                                        sectionPatch,
                                        file.additions(),
                                        file.deletions(),
                                        file.additions() + file.deletions());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // fall back to full patch
            }
        }
        return readFullPatch(executionId)
                .map(fullPatch -> extractFileDiff(path, fullPatch))
                .orElse(new DiffFileDto(path, "", 0, 0, 0));
    }

    private Optional<String> readFullPatch(UUID executionId) {
        String patchStoragePath = "diffs/" + executionId + ".patch";
        if (!blobStorageService.exists(patchStoragePath)) {
            return Optional.empty();
        }
        try (InputStream is = blobStorageService.getObject(patchStoragePath)) {
            return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
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
