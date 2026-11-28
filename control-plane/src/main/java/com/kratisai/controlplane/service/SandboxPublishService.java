package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ActivityDetail;
import com.kratisai.controlplane.api.wsdto.ActivityDetail.ActivityAttachment;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.ActivityType;
import com.kratisai.controlplane.api.wsdto.AttachmentKind;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.AttachmentResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.PreviewResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload.Attachment;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload.Preview;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionActivity;
import com.kratisai.controlplane.model.event.SandboxExecutionActivityEvent;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes sandbox results to the user.
 *
 * <p>Two publication kinds ride the existing environment WebSocket as {@code env.preview} and {@code
 * env.attachment} requests. The control plane owns the address translation: the sidecar reports a
 * sandbox-local port or a file, and this service computes a control-plane-origin URL the user can
 * open, records it in the task activity log, and broadcasts it to the web client.
 *
 * <ul>
 *   <li>Preview: the user-facing URL is the control plane's own {@code /preview/<envId>/<port>}
 *       path; the control plane proxies it to the sandbox-local port. The sandbox address never
 *       reaches the user.
 *   <li>Attachment: the file bytes are stored in blob storage under an unguessable id and served
 *       inline; the activity log carries the metadata the web UI renders as an expandable
 *       thumbnail.
 * </ul>
 */
@Service
public class SandboxPublishService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxPublishService.class);

    /** Decoded attachment size cap, matching the sidecar's outbound guard (6 MB decodes to ~8 MB base64). */
    public static final long MAX_ATTACHMENT_BYTES = 6L * 1024 * 1024;

    private static final List<String> IMAGE_CONTENT_TYPES =
            List.of("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/svg+xml");
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown";

    /**
     * Attachment ids become a single path segment under {@code attachments/<environmentId>/}, so
     * the id must not contain a separator or a traversal segment. The allowlist is deliberately
     * narrow: the id is agent-controlled and a {@code ../..} would escape the per-environment
     * subtree and overwrite another environment's attachments or the diff-blob tree.
     */
    private static final Pattern ATTACHMENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final ExecutionActivityPersistenceService activityPersistenceService;
    private final BlobStorageService blobStorageService;
    private final SandboxPreviewReachability reachability;
    private final ApplicationEventPublisher eventPublisher;

    public SandboxPublishService(
            ExecutionActivityPersistenceService activityPersistenceService,
            BlobStorageService blobStorageService,
            SandboxPreviewReachability reachability,
            ApplicationEventPublisher eventPublisher) {
        this.activityPersistenceService = activityPersistenceService;
        this.blobStorageService = blobStorageService;
        this.reachability = reachability;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publish a sandbox application preview.
     *
     * @param execution the execution the sidecar attributes the preview to (already authorized
     *     against the session's environment by the handler)
     * @param params the preview request from the sidecar
     * @return the control-plane-origin URL the user opens
     */
    @Transactional
    public PreviewResult publishPreview(SandboxExecution execution, Preview params) {
        UUID environmentId = execution.getEnvironment().getId();
        UUID teamId = execution.getChat().getTeam().getId();

        String previewUrl = previewUrl(environmentId, params.port(), params.path());
        ActivityDetail detail = ActivityDetail.forPreview(previewUrl, params.label());

        SandboxExecutionActivity row = activityPersistenceService.recordActivity(
                execution.getId(),
                ActivityType.PUBLISH,
                describePreview(params),
                "preview:" + params.port(),
                ActivityStatus.COMPLETED,
                detail);
        publishActivity(teamId, row);

        logger.info(
                "Published preview for environment {} execution {}: {} -> {}",
                environmentId,
                execution.getId(),
                params.label(),
                previewUrl);
        return new PreviewResult(previewUrl);
    }

    /**
     * Publish an image or markdown attachment.
     *
     * @param execution the execution the sidecar attributes the attachment to (already authorized
     *     against the session's environment by the handler)
     * @param params the attachment request from the sidecar
     * @return the attachment id and its inline view URL
     */
    @Transactional
    public AttachmentResult publishAttachment(SandboxExecution execution, Attachment params) {
        UUID environmentId = execution.getEnvironment().getId();
        UUID teamId = execution.getChat().getTeam().getId();

        byte[] content = decodeContent(params.contentBase64());
        validateAttachment(params.kind(), params.contentType(), content.length);
        validateAttachmentId(params.attachmentId());

        String blobPath = "attachments/" + environmentId + "/" + params.attachmentId();
        blobStorageService.putObject(blobPath, content, params.contentType());
        blobStorageService.putText(blobPath + ".content-type", params.contentType());
        String viewUrl = attachmentViewUrl(environmentId, params.attachmentId());

        ActivityAttachment attachment = new ActivityAttachment(
                params.attachmentId(), params.kind().getValue(), params.title(), params.contentType(), viewUrl);
        ActivityDetail detail = ActivityDetail.forAttachment(attachment);

        SandboxExecutionActivity row = activityPersistenceService.recordActivity(
                execution.getId(),
                ActivityType.ATTACHMENT,
                describeAttachment(params),
                "attachment:" + params.attachmentId(),
                ActivityStatus.COMPLETED,
                detail);
        publishActivity(teamId, row);

        logger.info(
                "Published attachment {} ({} bytes, {}) for environment {} execution {}",
                params.attachmentId(),
                content.length,
                params.contentType(),
                environmentId,
                execution.getId());
        return new AttachmentResult(params.attachmentId(), viewUrl);
    }

    /**
     * Resolve the upstream the preview proxy dials for a published sandbox port. The control plane
     * owns the sandbox-to-user address translation, so the user only ever sees the control-plane
     * origin.
     */
    public Optional<String> previewUpstream(UUID environmentId, int port) {
        try {
            return Optional.of(reachability.resolveUpstream(environmentId, port));
        } catch (SandboxPreviewReachability.PreviewUnreachableException e) {
            logger.warn(
                    "Preview upstream unreachable for environment {} port {}: {}", environmentId, port, e.getMessage());
            return Optional.empty();
        }
    }

    /** The control-plane-origin preview URL. Kept relative so the web UI opens it on its own origin. */
    String previewUrl(UUID environmentId, int port, String path) {
        String suffix = (path == null || path.isBlank()) ? "" : normalizePath(path);
        return "/preview/" + environmentId + "/" + port + suffix;
    }

    /** The public inline view URL for a stored attachment. */
    String attachmentViewUrl(UUID environmentId, String attachmentId) {
        return "/attachments/" + environmentId + "/" + attachmentId;
    }

    /** A stored attachment's bytes and stored content type, or null when the blob is absent. */
    public record AttachmentContent(byte[] bytes, String contentType) {}

    /**
     * Load a stored attachment for the public inline view. The URL is a capability: it embeds the
     * unguessable environment id and attachment id, so the endpoint can be permitAll for {@code <img
     * src>} while remaining unlinkable.
     */
    public AttachmentContent loadAttachment(UUID environmentId, String attachmentId) {
        if (!isValidAttachmentId(attachmentId)) {
            return null;
        }
        String blobPath = "attachments/" + environmentId + "/" + attachmentId;
        byte[] bytes = blobStorageService.getObjectBytes(blobPath);
        if (bytes == null) {
            return null;
        }
        byte[] typeBytes = blobStorageService.getObjectBytes(blobPath + ".content-type");
        String contentType = typeBytes != null
                ? new String(typeBytes, java.nio.charset.StandardCharsets.UTF_8).strip()
                : "application/octet-stream";
        return new AttachmentContent(bytes, contentType);
    }

    private String describePreview(Preview params) {
        String base = "Published preview '" + params.label() + "' on sandbox port " + params.port();
        return params.description() == null || params.description().isBlank()
                ? base
                : base + " — " + params.description();
    }

    private String describeAttachment(Attachment params) {
        return "Published " + params.kind().getValue() + " attachment '" + params.title() + "'";
    }

    private String normalizePath(String path) {
        String trimmed = path.strip();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return trimmed;
    }

    private byte[] decodeContent(String contentBase64) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contentBase64 is not valid base64");
        }
    }

    private void validateAttachment(AttachmentKind kind, String contentType, int length) {
        if (length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("Attachment exceeds the " + MAX_ATTACHMENT_BYTES + " byte cap");
        }
        if (kind == AttachmentKind.IMAGE) {
            if (!IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported image content type: " + contentType);
            }
        } else if (kind == AttachmentKind.MARKDOWN) {
            if (!MARKDOWN_CONTENT_TYPE.equals(contentType.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported markdown content type: " + contentType);
            }
        } else {
            throw new IllegalArgumentException("Unsupported attachment kind: " + kind);
        }
    }

    private boolean isValidAttachmentId(String attachmentId) {
        return attachmentId != null
                && !attachmentId.isEmpty()
                && !".".equals(attachmentId)
                && !"..".equals(attachmentId)
                && ATTACHMENT_ID_PATTERN.matcher(attachmentId).matches();
    }

    private void validateAttachmentId(String attachmentId) {
        if (!isValidAttachmentId(attachmentId)) {
            throw new IllegalArgumentException(
                    "Invalid attachmentId: must start with a letter or digit and contain only [A-Za-z0-9._-] (max 128)");
        }
    }

    private void publishActivity(UUID teamId, SandboxExecutionActivity row) {
        eventPublisher.publishEvent(new SandboxExecutionActivityEvent(
                teamId,
                row.getExecutionId(),
                row.getActivityType(),
                row.getDescription(),
                row.getActionId(),
                row.getStatus(),
                activityPersistenceService.detailOf(row),
                row.getCreatedAt()));
    }
}
