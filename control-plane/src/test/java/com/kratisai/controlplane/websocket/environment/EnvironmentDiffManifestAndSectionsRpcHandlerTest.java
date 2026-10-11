package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    @Test
    void sectionsHandler_exposesMethodNameAndPayloadType() {
        assertThat(sectionsHandler.getMethodName()).isEqualTo(EnvironmentRpcPayload.DiffSections.METHOD);
        assertThat(sectionsHandler.getPayloadType()).isEqualTo(EnvironmentRpcPayload.DiffSections.class);
    }

    @Test
    void sectionsHandler_skipsAlreadyStoredSection() {
        String data = "diff --git a/file b/file\n";
        String sha = sha256(data.getBytes(StandardCharsets.UTF_8));
        when(blobStorageService.exists("diffs/" + execId + "/" + sha)).thenReturn(true);

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

        verify(blobStorageService, never()).putObject(any(), any(InputStream.class), anyLong(), any());
        verify(blobStorageService, never()).deleteObject(any());
    }

    @Test
    void sectionsHandler_returnsEmptyStored_whenNoParts() {
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of());

        StepVerifier.create(sectionsHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    DiffSectionsResult r = (DiffSectionsResult) res;
                    assertThat(r.status()).isEqualTo(DiffSectionsStatus.STORED);
                    assertThat(r.missing()).isEmpty();
                })
                .verifyComplete();

        verifyNoInteractions(blobStorageService);
    }

    @Test
    void sectionsHandler_reportsMissing_whenMultipartIncomplete() {
        String full = "diff --git a/big b/big\nline1\nline2\nline3\n";
        String sha = sha256(full.getBytes(StandardCharsets.UTF_8));
        String chunk0 = full.substring(0, full.length() / 2);
        String part0Path = "diffs/" + execId + "/" + sha + ".part0";
        String part1Path = "diffs/" + execId + "/" + sha + ".part1";
        when(blobStorageService.exists(part0Path)).thenReturn(true);
        when(blobStorageService.exists(part1Path)).thenReturn(false);

        EnvironmentConnectorResult.DiffSectionPart part =
                new EnvironmentConnectorResult.DiffSectionPart(sha, 0, 2, chunk0);
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of(part));

        StepVerifier.create(sectionsHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    DiffSectionsResult r = (DiffSectionsResult) res;
                    assertThat(r.status()).isEqualTo(DiffSectionsStatus.STORED);
                    assertThat(r.missing()).containsExactly(sha);
                })
                .verifyComplete();

        verify(blobStorageService)
                .putObject(eq(part0Path), any(InputStream.class), eq((long) chunk0.length()), eq("text/plain"));
        verify(blobStorageService, never()).deleteObject(any());
        verify(blobStorageService, never())
                .putObject(eq("diffs/" + execId + "/" + sha), any(InputStream.class), anyLong(), any());
    }

    @Test
    void sectionsHandler_assemblesMultipart_whenFinalPartArrives() {
        String full = "diff --git a/big b/big\nassembled-content\n";
        String sha = sha256(full.getBytes(StandardCharsets.UTF_8));
        String chunk0 = full.substring(0, full.length() / 2);
        String chunk1 = full.substring(full.length() / 2);
        String finalPath = "diffs/" + execId + "/" + sha;
        String part0Path = finalPath + ".part0";
        String part1Path = finalPath + ".part1";
        when(blobStorageService.exists(part0Path)).thenReturn(true);
        when(blobStorageService.exists(part1Path)).thenReturn(true);
        when(blobStorageService.getObject(part0Path))
                .thenReturn(new ByteArrayInputStream(chunk0.getBytes(StandardCharsets.UTF_8)));
        when(blobStorageService.getObject(part1Path))
                .thenReturn(new ByteArrayInputStream(chunk1.getBytes(StandardCharsets.UTF_8)));

        EnvironmentConnectorResult.DiffSectionPart part =
                new EnvironmentConnectorResult.DiffSectionPart(sha, 1, 2, chunk1);
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of(part));

        StepVerifier.create(sectionsHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    DiffSectionsResult r = (DiffSectionsResult) res;
                    assertThat(r.status()).isEqualTo(DiffSectionsStatus.STORED);
                    assertThat(r.missing()).isEmpty();
                })
                .verifyComplete();

        byte[] fullBytes = full.getBytes(StandardCharsets.UTF_8);
        verify(blobStorageService)
                .putObject(eq(finalPath), any(InputStream.class), eq((long) fullBytes.length), eq("text/plain"));
        verify(blobStorageService).deleteObject(part0Path);
        verify(blobStorageService).deleteObject(part1Path);
    }

    @Test
    void sectionsHandler_throwsShaMismatch_whenAssembledMultipartCorrupt() {
        String real = "real diff content";
        String claimedSha = sha256("something else".getBytes(StandardCharsets.UTF_8));
        String chunk0 = real.substring(0, real.length() / 2);
        String chunk1 = real.substring(real.length() / 2);
        String part0Path = "diffs/" + execId + "/" + claimedSha + ".part0";
        String part1Path = "diffs/" + execId + "/" + claimedSha + ".part1";
        when(blobStorageService.exists(part0Path)).thenReturn(true);
        when(blobStorageService.exists(part1Path)).thenReturn(true);
        when(blobStorageService.getObject(part0Path))
                .thenReturn(new ByteArrayInputStream(chunk0.getBytes(StandardCharsets.UTF_8)));
        when(blobStorageService.getObject(part1Path))
                .thenReturn(new ByteArrayInputStream(chunk1.getBytes(StandardCharsets.UTF_8)));

        EnvironmentConnectorResult.DiffSectionPart part =
                new EnvironmentConnectorResult.DiffSectionPart(claimedSha, 1, 2, chunk1);
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of(part));

        assertThatThrownBy(() -> sectionsHandler.handle(sessionId, 1, params).blockFirst())
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().data().toString())
                                .contains("SHA mismatch for assembled section"));
    }

    @Test
    void sectionsHandler_wrapsIOException_whenPartReadFails() {
        String full = "diff content that fails to read";
        String sha = sha256(full.getBytes(StandardCharsets.UTF_8));
        String chunk = full.substring(full.length() / 2);
        String part0Path = "diffs/" + execId + "/" + sha + ".part0";
        String part1Path = "diffs/" + execId + "/" + sha + ".part1";
        when(blobStorageService.exists(part0Path)).thenReturn(true);
        when(blobStorageService.exists(part1Path)).thenReturn(true);
        when(blobStorageService.getObject(part0Path)).thenReturn(failingStream());
        when(blobStorageService.getObject(part1Path))
                .thenReturn(new ByteArrayInputStream(chunk.getBytes(StandardCharsets.UTF_8)));

        EnvironmentConnectorResult.DiffSectionPart part =
                new EnvironmentConnectorResult.DiffSectionPart(sha, 1, 2, chunk);
        EnvironmentRpcPayload.DiffSections params =
                new EnvironmentRpcPayload.DiffSections(execId.toString(), "digest-1", List.of(part));

        assertThatThrownBy(() -> sectionsHandler.handle(sessionId, 1, params).blockFirst())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed reading part " + part0Path);
    }

    @Test
    void sectionsHandler_handlesMixedBatch_skippedStoredAndIncomplete() {
        String storedData = "already stored";
        String storedSha = sha256(storedData.getBytes(StandardCharsets.UTF_8));
        when(blobStorageService.exists("diffs/" + execId + "/" + storedSha)).thenReturn(true);

        String singleData = "fresh single part";
        String singleSha = sha256(singleData.getBytes(StandardCharsets.UTF_8));

        String full = "multipart full content for mixed batch";
        String multiSha = sha256(full.getBytes(StandardCharsets.UTF_8));
        String chunk0 = full.substring(0, full.length() / 2);
        String multiPart0Path = "diffs/" + execId + "/" + multiSha + ".part0";
        String multiPart1Path = "diffs/" + execId + "/" + multiSha + ".part1";
        when(blobStorageService.exists(multiPart0Path)).thenReturn(true);
        when(blobStorageService.exists(multiPart1Path)).thenReturn(false);

        EnvironmentRpcPayload.DiffSections params = new EnvironmentRpcPayload.DiffSections(
                execId.toString(),
                "digest-1",
                List.of(
                        new EnvironmentConnectorResult.DiffSectionPart(storedSha, 0, 1, storedData),
                        new EnvironmentConnectorResult.DiffSectionPart(singleSha, 0, 1, singleData),
                        new EnvironmentConnectorResult.DiffSectionPart(multiSha, 0, 2, chunk0)));

        StepVerifier.create(sectionsHandler.handle(sessionId, 1, params))
                .assertNext(res -> {
                    DiffSectionsResult r = (DiffSectionsResult) res;
                    assertThat(r.status()).isEqualTo(DiffSectionsStatus.STORED);
                    assertThat(r.missing()).containsExactly(multiSha);
                })
                .verifyComplete();

        verify(blobStorageService, never())
                .putObject(eq("diffs/" + execId + "/" + storedSha), any(InputStream.class), anyLong(), any());
        verify(blobStorageService)
                .putObject(
                        eq("diffs/" + execId + "/" + singleSha),
                        any(InputStream.class),
                        eq((long) singleData.length()),
                        eq("text/plain"));
    }

    private static InputStream failingStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
