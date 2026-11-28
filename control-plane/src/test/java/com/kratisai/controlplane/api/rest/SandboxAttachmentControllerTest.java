package com.kratisai.controlplane.api.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.service.SandboxPublishService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SandboxAttachmentControllerTest {

    @Mock
    private SandboxPublishService publishService;

    private MockMvc mockMvc;

    private final UUID environmentId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SandboxAttachmentController(publishService))
                .build();
    }

    @Test
    void viewAttachment_servesStoredBytesWithStoredContentType() throws Exception {
        byte[] bytes = "<h1>Report</h1>".getBytes(StandardCharsets.UTF_8);
        when(publishService.loadAttachment(environmentId, "shot-1"))
                .thenReturn(new SandboxPublishService.AttachmentContent(bytes, "image/png"));

        mockMvc.perform(get("/attachments/{environmentId}/{attachmentId}", environmentId, "shot-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"shot-1\""));
    }

    @Test
    void viewAttachment_missingBlob_returns404() throws Exception {
        when(publishService.loadAttachment(environmentId, "missing")).thenReturn(null);

        mockMvc.perform(get("/attachments/{environmentId}/{attachmentId}", environmentId, "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewAttachment_malformedContentType_returnsOctetStream() throws Exception {
        byte[] bytes = "plain".getBytes(StandardCharsets.UTF_8);
        when(publishService.loadAttachment(environmentId, "a1"))
                .thenReturn(new SandboxPublishService.AttachmentContent(bytes, "not/a/type"));

        mockMvc.perform(get("/attachments/{environmentId}/{attachmentId}", environmentId, "a1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void viewAttachment_badEnvironmentId_returns400() throws Exception {
        mockMvc.perform(get("/attachments/{environmentId}/{attachmentId}", "not-a-uuid", "a1"))
                .andExpect(status().isBadRequest());
    }
}
