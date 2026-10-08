package com.kratisai.controlplane.websocket.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.EnvironmentDiffChangedResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionDiffChangedEvent;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.service.BlobStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentDiffChangedRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.DiffChanged, EnvironmentResponsePayload> {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentDiffChangedRpcHandler.class);

    private final EnvironmentExecutionGuard executionGuard;
    private final ExecutionDiffSnapshotRepository diffSnapshotRepository;
    private final BlobStorageService blobStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public EnvironmentDiffChangedRpcHandler(
            EnvironmentExecutionGuard executionGuard,
            ExecutionDiffSnapshotRepository diffSnapshotRepository,
            BlobStorageService blobStorageService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.executionGuard = executionGuard;
        this.diffSnapshotRepository = diffSnapshotRepository;
        this.blobStorageService = blobStorageService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.DiffChanged.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.DiffChanged> getPayloadType() {
        return EnvironmentRpcPayload.DiffChanged.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.DiffChanged params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        String patchStoragePath = "diffs/" + execId + ".patch";
        byte[] patchBytes = params.patch().getBytes(StandardCharsets.UTF_8);
        // Must not be caught: the patch is the only diff copy surviving sandbox
        // teardown. Startup validation (BlobStorageValidator) guarantees the store
        // is writable; a failure here fails the stream so the sidecar retries.
        blobStorageService.putObject(
                patchStoragePath, new ByteArrayInputStream(patchBytes), patchBytes.length, "text/plain");

        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(params.files());
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize diff files summary for execution {}", execId, e);
            summaryJson = "[]";
        }

        String finalSummary = summaryJson;
        ExecutionDiffSnapshot snapshot = diffSnapshotRepository
                .findByExecutionId(execId)
                .orElseGet(() -> new ExecutionDiffSnapshot(
                        execId,
                        params.baseCommit(),
                        params.headCommit(),
                        params.totalAdditions(),
                        params.totalDeletions(),
                        finalSummary,
                        patchStoragePath));

        snapshot.setBaseCommit(params.baseCommit());
        snapshot.setHeadCommit(params.headCommit());
        snapshot.setTotalAdditions(params.totalAdditions());
        snapshot.setTotalDeletions(params.totalDeletions());
        snapshot.setSummaryJson(summaryJson);
        snapshot.setPatchStoragePath(patchStoragePath);
        diffSnapshotRepository.save(snapshot);

        UUID teamId = execution.getChat().getTeam().getId();
        UUID chatId = execution.getChat().getId();
        eventPublisher.publishEvent(new SandboxExecutionDiffChangedEvent(teamId, chatId, execId));

        return Flux.just(new EnvironmentDiffChangedResult());
    }
}
