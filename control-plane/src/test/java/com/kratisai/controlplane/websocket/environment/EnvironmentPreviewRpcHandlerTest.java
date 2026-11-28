package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.AttachmentKind;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload.Attachment;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload.Preview;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.BlobStorageService;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.ExecutionActivityPersistenceService;
import com.kratisai.controlplane.service.SandboxPreviewReachability;
import com.kratisai.controlplane.service.SandboxPublishService;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class EnvironmentPreviewRpcHandlerTest {

    private static final UUID ENV_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EXECUTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private SandboxExecutionRepository executionRepository;

    @Mock
    private ExecutionActivityPersistenceService activityPersistenceService;

    @Mock
    private BlobStorageService blobStorageService;

    @Mock
    private SandboxPreviewReachability reachability;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EnvironmentPreviewRpcHandler previewHandler;
    private EnvironmentAttachmentRpcHandler attachmentHandler;

    @BeforeEach
    void setUp() {
        EnvironmentExecutionGuard guard = new EnvironmentExecutionGuard(sessionRegistry, executionRepository);
        SandboxPublishService publishService =
                new SandboxPublishService(activityPersistenceService, blobStorageService, reachability, eventPublisher);
        previewHandler = new EnvironmentPreviewRpcHandler(guard, publishService);
        attachmentHandler = new EnvironmentAttachmentRpcHandler(guard, publishService);
    }

    private SandboxExecution execution() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        ChatEntity chat = new ChatEntity();
        chat.setTeam(team);
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(ENV_ID);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(EXECUTION_ID);
        execution.setChat(chat);
        execution.setEnvironment(env);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        return execution;
    }

    private void sessionResolvesExecution() {
        when(sessionRegistry.getEnvironmentId("ws-1")).thenReturn(Optional.of(ENV_ID));
        when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution()));
    }

    @Test
    void previewHandlerReturnsControlPlaneOriginUrlAndRecordsActivity() {
        sessionResolvesExecution();
        when(activityPersistenceService.recordActivity(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(activityRow());

        Preview params = new Preview(EXECUTION_ID.toString(), 3000, "Web app", "/health", "note");

        Flux<EnvironmentResponsePayload> result = previewHandler.handle("ws-1", 1L, params);

        StepVerifier.create(result)
                .assertNext(payload -> {
                    EnvironmentResponsePayload.PreviewResult published =
                            (EnvironmentResponsePayload.PreviewResult) payload;
                    assertThat(published.previewUrl()).isEqualTo("/preview/" + ENV_ID + "/3000/health");
                    assertThat(published.status()).isEqualTo(EnvironmentResponsePayload.PreviewStatus.PUBLISHED);
                })
                .verifyComplete();

        verify(activityPersistenceService)
                .recordActivity(
                        eq(EXECUTION_ID),
                        eq(ActivityType.PUBLISH),
                        eq("Published preview 'Web app' on sandbox port 3000 — note"),
                        eq("preview:3000"),
                        eq(ActivityStatus.COMPLETED),
                        any());
        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void previewHandlerRejectsPortOutOfRange() {
        assertThatThrownBy(() -> new Preview(EXECUTION_ID.toString(), 70000, "Web app", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void attachmentHandlerStoresBytesAndReturnsViewUrl() {
        sessionResolvesExecution();
        when(activityPersistenceService.recordActivity(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(activityRow());

        byte[] bytes = new byte[] {1, 2, 3, 4};
        Attachment params = new Attachment(
                EXECUTION_ID.toString(),
                "shot-1",
                AttachmentKind.IMAGE,
                "Checkout screenshot",
                "image/png",
                Base64.getEncoder().encodeToString(bytes),
                "a shot");

        Flux<EnvironmentResponsePayload> result = attachmentHandler.handle("ws-1", 1L, params);

        StepVerifier.create(result)
                .assertNext(payload -> {
                    EnvironmentResponsePayload.AttachmentResult published =
                            (EnvironmentResponsePayload.AttachmentResult) payload;
                    assertThat(published.attachmentId()).isEqualTo("shot-1");
                    assertThat(published.viewUrl()).isEqualTo("/attachments/" + ENV_ID + "/shot-1");
                    assertThat(published.status()).isEqualTo(EnvironmentResponsePayload.AttachmentStatus.PUBLISHED);
                })
                .verifyComplete();

        verify(blobStorageService).putObject(eq("attachments/" + ENV_ID + "/shot-1"), eq(bytes), eq("image/png"));
        verify(blobStorageService).putText("attachments/" + ENV_ID + "/shot-1.content-type", "image/png");
        verify(activityPersistenceService)
                .recordActivity(
                        eq(EXECUTION_ID),
                        eq(ActivityType.ATTACHMENT),
                        eq("Published image attachment 'Checkout screenshot'"),
                        eq("attachment:shot-1"),
                        eq(ActivityStatus.COMPLETED),
                        any());
        verify(eventPublisher).publishEvent(any(SandboxExecutionActivityEvent.class));
    }

    @Test
    void attachmentHandlerRejectsNonImageContentType() {
        sessionResolvesExecution();

        Attachment params = new Attachment(
                EXECUTION_ID.toString(),
                "doc-1",
                AttachmentKind.IMAGE,
                "Doc",
                "application/pdf",
                Base64.getEncoder().encodeToString(new byte[] {1}),
                null);

        assertThatThrownBy(() -> attachmentHandler.handle("ws-1", 1L, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image content type");
    }

    @Test
    void attachmentHandlerRejectsOversizedPayload() {
        sessionResolvesExecution();

        byte[] oversized = new byte[(int) SandboxPublishService.MAX_ATTACHMENT_BYTES + 1];
        Attachment params = new Attachment(
                EXECUTION_ID.toString(),
                "big-1",
                AttachmentKind.IMAGE,
                "Big",
                "image/png",
                Base64.getEncoder().encodeToString(oversized),
                null);

        assertThatThrownBy(() -> attachmentHandler.handle("ws-1", 1L, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void handlersRejectExecutionFromAnotherEnvironment() {
        when(sessionRegistry.getEnvironmentId("ws-1")).thenReturn(Optional.of(ENV_ID));
        ExecutionEnvironment otherEnv = new ExecutionEnvironment();
        otherEnv.setId(UUID.randomUUID());
        SandboxExecution execution = execution();
        execution.setEnvironment(otherEnv);
        when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));

        Preview preview = new Preview(EXECUTION_ID.toString(), 3000, "Web app", null, null);

        assertThatThrownBy(() -> previewHandler.handle("ws-1", 1, preview))
                .isInstanceOf(RpcErrorException.class)
                .satisfies(
                        e -> assertThat(((RpcErrorException) e).error().code()).isEqualTo(-32002));
    }

    @Test
    void handlersDeclareMethodAndPayloadType() {
        assertThat(previewHandler.getMethodName()).isEqualTo("env.preview");
        assertThat(previewHandler.getPayloadType()).isEqualTo(Preview.class);
        assertThat(attachmentHandler.getMethodName()).isEqualTo("env.attachment");
        assertThat(attachmentHandler.getPayloadType()).isEqualTo(Attachment.class);
    }

    private SandboxExecutionActivity activityRow() {
        SandboxExecutionActivity row = new SandboxExecutionActivity();
        row.setExecutionId(EXECUTION_ID);
        row.setActivityType(ActivityType.PUBLISH);
        row.setStatus(ActivityStatus.COMPLETED);
        row.setDescription("desc");
        row.setActionId("action");
        row.setCreatedAt(java.time.Instant.now());
        return row;
    }
}
