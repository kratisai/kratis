package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.CanvasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chats")
@Tag(name = "Chat Canvas", description = "Canvas document management endpoints scoped to chats")
@SecurityRequirement(name = "bearerAuth")
public class CanvasController {

    private final CanvasService canvasService;

    public CanvasController(CanvasService canvasService) {
        this.canvasService = canvasService;
    }

    @DeleteMapping("/{chatId}/canvas/documents/{documentId}")
    @Operation(summary = "Delete a canvas document", description = "Soft-deletes a canvas document within a chat")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Canvas document deleted"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content),
                @ApiResponse(
                        responseCode = "404",
                        description = "Chat or canvas document not found",
                        content = @Content)
            })
    public ResponseEntity<Void> deleteCanvasDocument(@PathVariable UUID chatId, @PathVariable String documentId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        canvasService.deleteCanvas(userId, chatId, documentId);
        return ResponseEntity.noContent().build();
    }
}
