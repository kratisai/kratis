package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.restdto.CreateChatRequest;
import com.kratisai.controlplane.api.restdto.CreateChatResponse;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.planningagent.PlanningAgentService;
import com.kratisai.controlplane.service.ChatFluxRegistry;
import com.kratisai.controlplane.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chats")
@Tag(name = "Chats", description = "Chat management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final PlanningAgentService planningAgentService;
    private final ChatFluxRegistry chatFluxRegistry;

    public ChatController(
            ChatService chatService, PlanningAgentService planningAgentService, ChatFluxRegistry chatFluxRegistry) {
        this.chatService = chatService;
        this.planningAgentService = planningAgentService;
        this.chatFluxRegistry = chatFluxRegistry;
    }

    @GetMapping
    @Operation(
            summary = "List chats for a team",
            description =
                    "Returns the last 20 chats for a team, optionally filtered by user filter and status (active, archived, all)")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "List of chats",
                        content = @Content(schema = @Schema(implementation = ChatDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<List<ChatDto>> listChats(
            @RequestParam UUID teamId,
            @RequestParam(defaultValue = "mine") String filter,
            @RequestParam(defaultValue = "active") String status) {
        UUID userId = SecurityUtil.getCurrentUserId();
        boolean onlyMine = "mine".equalsIgnoreCase(filter);
        return ResponseEntity.ok(chatService.listChatsForTeam(teamId, userId, onlyMine, status));
    }

    @PostMapping
    @Operation(
            summary = "Create a new chat",
            description =
                    "Synchronously generates a summary title, creates a chat DB record, triggers background agent execution, and broadcasts entity change event")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "202",
                        description = "Chat created and execution started",
                        content = @Content(schema = @Schema(implementation = CreateChatResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request params", content = @Content),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<CreateChatResponse> createChat(@RequestBody CreateChatRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        String message = request.message();
        String title = planningAgentService.generateInitialTitle(
                request.teamId(), request.providerId(), request.modelName(), message);

        ChatEntity chat = chatService.createChat(request.teamId(), userId, title);

        // Start execution in background (registered so subscribers receive updates via chat.subscribe WS channel)
        var streamFlux = planningAgentService.streamMessage(
                request.teamId(), request.providerId(), request.modelName(), chat.getId(), message);
        chatFluxRegistry.register(chat.getId(), streamFlux);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CreateChatResponse(chat.getId(), chat.getCreatedAt().toString()));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive a chat", description = "Marks a chat as archived")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Chat archived successfully"),
                @ApiResponse(responseCode = "404", description = "Chat not found"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team")
            })
    public ResponseEntity<Void> archiveChat(@PathVariable UUID id) {
        UUID userId = SecurityUtil.getCurrentUserId();
        chatService.archiveChat(id, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/archive")
    @Operation(summary = "Unarchive a chat", description = "Removes the archived status from a chat")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Chat unarchived successfully"),
                @ApiResponse(responseCode = "404", description = "Chat not found"),
                @ApiResponse(responseCode = "403", description = "Not a member of this team")
            })
    public ResponseEntity<Void> unarchiveChat(@PathVariable UUID id) {
        UUID userId = SecurityUtil.getCurrentUserId();
        chatService.unarchiveChat(id, userId);
        return ResponseEntity.noContent().build();
    }
}
