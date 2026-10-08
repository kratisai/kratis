package com.kratisai.controlplane.websocket.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffManifestResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffManifestStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.event.SandboxExecutionDiffChangedEvent;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.service.BlobStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentDiffManifestRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.DiffManifest, EnvironmentResponsePayload> {

    private final EnvironmentExecutionGuard executionGuard;
    private final ExecutionDiffSnapshotRepository diffSnapshotRepository;
    private final BlobStorageService blobStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public EnvironmentDiffManifestRpcHandler(
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
        return EnvironmentRpcPayload.DiffManifest.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.DiffManifest> getPayloadType() {
        return EnvironmentRpcPayload.DiffManifest.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.DiffManifest params) {
        UUID execId = UUID.fromString(params.executionId());
        SandboxExecution execution = executionGuard.requireExecutionInEnvironment(sessionId, execId);

        Optional<ExecutionDiffSnapshot> existing = diffSnapshotRepository.findByExecutionId(execId);
        if (existing.isPresent()
                && params.manifestDigest().equals(existing.get().getManifestDigest())) {
            return Flux.just(new DiffManifestResult(DiffManifestStatus.COMMITTED, List.of()));
        }

        List<String> missing = new ArrayList<>();
        for (EnvironmentConnectorResult.GitDiffManifestFile file : params.files()) {
            String blobPath = "diffs/" + execId + "/" + file.sha();
            if (!blobStorageService.exists(blobPath)) {
                missing.add(file.sha());
            }
        }

        if (!missing.isEmpty()) {
            return Flux.just(new DiffManifestResult(DiffManifestStatus.INCOMPLETE, missing));
        }

        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(params.files());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize diff manifest for execution " + execId, e);
        }

        ExecutionDiffSnapshot snapshot = existing.orElseGet(() -> new ExecutionDiffSnapshot(
                execId,
                params.baseCommit(),
                params.headCommit(),
                params.totalAdditions(),
                params.totalDeletions(),
                summaryJson,
                params.manifestDigest()));

        snapshot.setBaseCommit(params.baseCommit());
        snapshot.setHeadCommit(params.headCommit());
        snapshot.setTotalAdditions(params.totalAdditions());
        snapshot.setTotalDeletions(params.totalDeletions());
        snapshot.setSummaryJson(summaryJson);
        snapshot.setManifestDigest(params.manifestDigest());
        diffSnapshotRepository.save(snapshot);

        UUID teamId = execution.getChat().getTeam().getId();
        UUID chatId = execution.getChat().getId();
        eventPublisher.publishEvent(new SandboxExecutionDiffChangedEvent(teamId, chatId, execId));

        return Flux.just(new DiffManifestResult(DiffManifestStatus.COMMITTED, List.of()));
    }
}
