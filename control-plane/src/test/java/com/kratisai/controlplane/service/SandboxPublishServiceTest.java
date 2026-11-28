package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.AttachmentKind;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload.Attachment;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload.Preview;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SandboxPublishServiceTest {

    private static final UUID ENV_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EXEC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TEAM_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private ExecutionActivityPersistenceService activityPersistenceService;
    private BlobStorageService blobStorageService;
    private SandboxPreviewReachability reachability;
    private ApplicationEventPublisher eventPublisher;
    private SandboxPublishService service;

    @BeforeEach
    void setUp() {
        activityPersistenceService = mock(ExecutionActivityPersistenceService.class);
        blobStorageService = mock(BlobStorageService.class);
        reachability = mock(SandboxPreviewReachability.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service =
                new SandboxPublishService(activityPersistenceService, blobStorageService, reachability, eventPublisher);
    }

    @Test
    void previewUrlIsControlPlaneRelativeAndCarriesEnvironmentIdPortAndPath() {
        String url = service.previewUrl(ENV_ID, 3000, "/health");
        assertThat(url).isEqualTo("/preview/" + ENV_ID + "/3000/health");
    }

    @Test
    void previewUrlNormalizesMissingLeadingSlashAndBlankPath() {
        assertThat(service.previewUrl(ENV_ID, 8080, "docs")).isEqualTo("/preview/" + ENV_ID + "/8080/docs");
        assertThat(service.previewUrl(ENV_ID, 8080, "")).isEqualTo("/preview/" + ENV_ID + "/8080");
        assertThat(service.previewUrl(ENV_ID, 8080, null)).isEqualTo("/preview/" + ENV_ID + "/8080");
    }

    @Test
    void publishPreviewRecordsActivityWithPreviewDetail() {
        SandboxExecution execution = execution();
        when(activityPersistenceService.recordActivity(
                        eq(EXEC_ID),
                        eq(ActivityType.PUBLISH),
                        anyString(),
                        anyString(),
                        eq(ActivityStatus.COMPLETED),
                        any()))
                .thenReturn(activityRow());

        com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.PreviewResult published =
                service.publishPreview(execution, new Preview(EXEC_ID.toString(), 3000, "Web app", "/health", "note"));

        assertThat(published.previewUrl()).isEqualTo("/preview/" + ENV_ID + "/3000/health");
        assertThat(published.status()).isEqualTo(EnvironmentResponsePayload.PreviewStatus.PUBLISHED);
        verify(activityPersistenceService)
                .recordActivity(
                        eq(EXEC_ID),
                        eq(ActivityType.PUBLISH),
                        eq("Published preview 'Web app' on sandbox port 3000 — note"),
                        eq("preview:3000"),
                        eq(ActivityStatus.COMPLETED),
                        any(ActivityDetail.class));
        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void publishAttachmentStoresBytesAndSidecarAndRecordsActivity() {
        SandboxExecution execution = execution();
        when(activityPersistenceService.recordActivity(
                        eq(EXEC_ID),
                        eq(ActivityType.ATTACHMENT),
                        anyString(),
                        anyString(),
                        eq(ActivityStatus.COMPLETED),
                        any()))
                .thenReturn(activityRow());

        byte[] bytes = new byte[] {1, 2, 3, 4};
        String base64 = Base64.getEncoder().encodeToString(bytes);
        Attachment params = new Attachment(
                EXEC_ID.toString(), "shot-1", AttachmentKind.IMAGE, "Checkout", "image/png", base64, "a shot");

        com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.AttachmentResult published =
                service.publishAttachment(execution, params);

        assertThat(published.attachmentId()).isEqualTo("shot-1");
        assertThat(published.viewUrl()).isEqualTo("/attachments/" + ENV_ID + "/shot-1");
        verify(blobStorageService).putObject(eq("attachments/" + ENV_ID + "/shot-1"), eq(bytes), eq("image/png"));
        verify(blobStorageService).putText("attachments/" + ENV_ID + "/shot-1.content-type", "image/png");
        verify(activityPersistenceService)
                .recordActivity(
                        eq(EXEC_ID),
                        eq(ActivityType.ATTACHMENT),
                        eq("Published image attachment 'Checkout'"),
                        eq("attachment:shot-1"),
                        eq(ActivityStatus.COMPLETED),
                        any(ActivityDetail.class));
    }

    @Test
    void loadAttachmentRoundTripsBytesAndContentType() {
        byte[] bytes = new byte[] {9, 9};
        when(blobStorageService.getObjectBytes("attachments/" + ENV_ID + "/a1")).thenReturn(bytes);
        when(blobStorageService.getObjectBytes("attachments/" + ENV_ID + "/a1.content-type"))
                .thenReturn("image/webp".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        SandboxPublishService.AttachmentContent content = service.loadAttachment(ENV_ID, "a1");

        assertThat(content).isNotNull();
        assertThat(content.bytes()).containsExactly(9, 9);
        assertThat(content.contentType()).isEqualTo("image/webp");
    }

    @Test
    void loadAttachmentReturnsNullWhenBlobMissing() {
        when(blobStorageService.getObjectBytes(anyString())).thenReturn(null);
        assertThat(service.loadAttachment(ENV_ID, "missing")).isNull();
    }

    @Test
    void attachmentRejectsNonImageContentType() {
        SandboxExecution execution = execution();
        Attachment params = new Attachment(
                EXEC_ID.toString(),
                "x",
                AttachmentKind.IMAGE,
                "t",
                "application/pdf",
                Base64.getEncoder().encodeToString(new byte[] {1}),
                null);
        assertThatThrownBy(() -> service.publishAttachment(execution, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image content type");
    }

    @Test
    void attachmentRejectsNonMarkdownContentType() {
        SandboxExecution execution = execution();
        Attachment params = new Attachment(
                EXEC_ID.toString(),
                "x",
                AttachmentKind.MARKDOWN,
                "t",
                "text/plain",
                Base64.getEncoder().encodeToString(new byte[] {1}),
                null);
        assertThatThrownBy(() -> service.publishAttachment(execution, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported markdown content type");
    }

    @Test
    void attachmentRejectsOversizedPayload() {
        SandboxExecution execution = execution();
        byte[] oversized = new byte[(int) SandboxPublishService.MAX_ATTACHMENT_BYTES + 1];
        Attachment params = new Attachment(
                EXEC_ID.toString(),
                "x",
                AttachmentKind.IMAGE,
                "t",
                "image/png",
                Base64.getEncoder().encodeToString(oversized),
                null);
        assertThatThrownBy(() -> service.publishAttachment(execution, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void attachmentRejectsInvalidBase64() {
        SandboxExecution execution = execution();
        Attachment params =
                new Attachment(EXEC_ID.toString(), "x", AttachmentKind.IMAGE, "t", "image/png", "not base64!!", null);
        assertThatThrownBy(() -> service.publishAttachment(execution, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void attachmentRejectsPathTraversalId() {
        SandboxExecution execution = execution();
        byte[] bytes = new byte[] {1, 2, 3};
        String base64 = Base64.getEncoder().encodeToString(bytes);
        for (String id : new String[] {"../../etc/passwd", "..", "a/b", "a\\b", ".hidden"}) {
            Attachment params =
                    new Attachment(EXEC_ID.toString(), id, AttachmentKind.IMAGE, "t", "image/png", base64, null);
            assertThatThrownBy(() -> service.publishAttachment(execution, params))
                    .as("attachmentId %s must be rejected", id)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid attachmentId");
        }
        verify(blobStorageService, org.mockito.Mockito.never()).putObject(anyString(), any(byte[].class), anyString());
    }

    @Test
    void loadAttachmentReturnsNullForTraversalIdWithoutReadingBlob() {
        assertThat(service.loadAttachment(ENV_ID, "../../etc/passwd")).isNull();
        verify(blobStorageService, org.mockito.Mockito.never()).getObjectBytes(anyString());
    }

    @Test
    void previewUpstreamDelegatesToReachabilityAndHandlesUnreachable() {
        when(reachability.resolveUpstream(ENV_ID, 3000)).thenReturn("http://10.210.1.3:3000");
        assertThat(service.previewUpstream(ENV_ID, 3000)).contains("http://10.210.1.3:3000");

        when(reachability.resolveUpstream(ENV_ID, 3001))
                .thenThrow(new SandboxPreviewReachability.PreviewUnreachableException("no route"));
        assertThat(service.previewUpstream(ENV_ID, 3001)).isEmpty();
    }

    private SandboxExecution execution() {
        Team team = new Team();
        team.setId(TEAM_ID);
        ChatEntity chat = new ChatEntity();
        chat.setId(EXEC_ID);
        chat.setTeam(team);
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(ENV_ID);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(EXEC_ID);
        execution.setEnvironment(environment);
        execution.setChat(chat);
        return execution;
    }

    private SandboxExecutionActivity activityRow() {
        SandboxExecutionActivity row = new SandboxExecutionActivity();
        row.setExecutionId(EXEC_ID);
        row.setActivityType(ActivityType.PUBLISH);
        row.setStatus(ActivityStatus.COMPLETED);
        row.setDescription("desc");
        row.setActionId("action");
        row.setCreatedAt(Instant.now());
        return row;
    }
}
