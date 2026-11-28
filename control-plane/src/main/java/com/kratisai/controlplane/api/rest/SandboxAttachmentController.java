package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.service.SandboxPublishService;
import com.kratisai.controlplane.service.SandboxPublishService.AttachmentContent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves published sandbox attachments inline.
 *
 * <p>The web UI renders an activity-log thumbnail with a plain {@code <img src>} or markdown link,
 * which cannot carry an Authorization header. The view URL embeds the unguessable environment id
 * and attachment id (both random UUIDs), so the URL itself is the capability: possession is the
 * only authorization, the same model as a signed share link. The blob lives in the control plane's
 * object store, never in the sandbox, and only images and markdown are ever stored.
 */
@RestController
@RequestMapping("/attachments")
public class SandboxAttachmentController {

    private static final Logger logger = LoggerFactory.getLogger(SandboxAttachmentController.class);

    private final SandboxPublishService publishService;

    public SandboxAttachmentController(SandboxPublishService publishService) {
        this.publishService = publishService;
    }

    @GetMapping("/{environmentId}/{attachmentId}")
    public ResponseEntity<byte[]> viewAttachment(@PathVariable UUID environmentId, @PathVariable String attachmentId) {
        AttachmentContent content = publishService.loadAttachment(environmentId, attachmentId);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(content.contentType());
        } catch (IllegalArgumentException e) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachmentId + "\"")
                .contentType(contentType)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(content.bytes());
    }
}
