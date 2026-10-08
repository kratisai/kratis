package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffManifestResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffManifestStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffSectionsResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffSectionsStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.GitDiffStatus;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.service.BlobStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.test.StepVerifier;

class EnvironmentDiffManifestAndSectionsRpcHandlerTest {

    private ExecutionDiffSnapshotRepository diffSnapshotRepository;
    private BlobStorageService blobStorageService;

    private EnvironmentDiffManifestRpcHandler manifestHandler;
    private EnvironmentDiffSectionsRpcHandler sectionsHandler;

    private final UUID execId = UUID.randomUUID();
    private final String sessionId = "session-123";

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard executionGuard = mock(EnvironmentExecutionGuard.class);
        diffSnapshotRepository = mock(ExecutionDiffSnapshotRepository.class);
        blobStorageService = mock(BlobStorageService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();

        manifestHandler = new EnvironmentDiffManifestRpcHandler(
                executionGuard, diffSnapshotRepository, blobStorageService, eventPublisher, objectMapper);
        sectionsHandler = new EnvironmentDiffSectionsRpcHandler(executionGuard, blobStorageService);

        SandboxExecution execution = mock(SandboxExecution.class);
        ChatEntity chat = mock(ChatEntity.class);
        Team team = mock(Team.class);
        when(execution.getChat()).thenReturn(chat);
        when(chat.getTeam()).thenReturn(team);
        when(chat.getId()).thenReturn(UUID.randomUUID());
        when(team.getId()).thenReturn(UUID.randomUUID());
        when(executionGuard.requireExecutionInEnvironment(eq(sessionId), eq(execId)))
                .thenReturn(execution);
    }

    @Test
    void manifestHandler_returnsCommitted_whenAlreadyCommittedWithSameDigest() {
        ExecutionDiffSnapshot existing = new ExecutionDiffSnapshot();
        existing.setManifestDigest("digest-123");
        when(diffSnapshotRepository.findByExecutionId(execId)).thenReturn(Optional.of(existing));

        EnvironmentRpcPayload.DiffManifest params = new EnvironmentRpcPayload.DiffManifest(
                execId.toString(), "base", "head", 1, 0, "digest-123", List.of());

        StepVerifier.create(manifestHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    assertThat(res).isInstanceOf(DiffManifestResult.class);
                    DiffManifestResult r = (DiffManifestResult) res;
                    assertThat(r.status()).isEqualTo(DiffManifestStatus.COMMITTED);
                    assertThat(r.missing()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void manifestHandler_returnsIncomplete_whenBlobsMissing() {
        when(diffSnapshotRepository.findByExecutionId(execId)).thenReturn(Optional.empty());
        when(blobStorageService.exists("diffs/" + execId + "/sha1")).thenReturn(false);

        EnvironmentConnectorResult.GitDiffManifestFile file = new EnvironmentConnectorResult.GitDiffManifestFile(
                "file.txt", GitDiffStatus.MODIFIED, 1, 0, false, "sha1", 10);
        EnvironmentRpcPayload.DiffManifest params = new EnvironmentRpcPayload.DiffManifest(
                execId.toString(), "base", "head", 1, 0, "digest-new", List.of(file));

        StepVerifier.create(manifestHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    assertThat(res).isInstanceOf(DiffManifestResult.class);
                    DiffManifestResult r = (DiffManifestResult) res;
                    assertThat(r.status()).isEqualTo(DiffManifestStatus.INCOMPLETE);
                    assertThat(r.missing()).containsExactly("sha1");
                })
                .verifyComplete();
    }

    @Test
    void sectionsHandler_storesSinglePartSection_andValidatesSha() {
        String data = "diff --git a/file b/file\n";
        String sha = sha256(data.getBytes(StandardCharsets.UTF_8));

        EnvironmentConnectorResult.DiffSectionPart part =
                new EnvironmentConnectorResult.DiffSectionPart(sha, 0, 1, data);
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of(part));

        StepVerifier.create(sectionsHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    assertThat(res).isInstanceOf(DiffSectionsResult.class);
                    DiffSectionsResult r = (DiffSectionsResult) res;
                    assertThat(r.status()).isEqualTo(DiffSectionsStatus.STORED);
                    assertThat(r.missing()).isEmpty();
                })
                .verifyComplete();

        verify(blobStorageService)
                .putObject(eq("diffs/" + execId + "/" + sha), any(), eq((long) data.length()), eq("text/plain"));
    }

    @Test
    void sectionsHandler_throwsShaMismatch_onCorruptData() {
        EnvironmentConnectorResult.DiffSectionPart part =
                new EnvironmentConnectorResult.DiffSectionPart("badsha", 0, 1, "data");
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of(part));

        assertThatThrownBy(() -> sectionsHandler.handle(sessionId, 1, params).blockFirst())
                .isInstanceOf(RpcErrorException.class);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
