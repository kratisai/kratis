package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.restdto.ChatUsageDto;
import com.kratisai.controlplane.api.restdto.LastActivityType;
import com.kratisai.controlplane.api.restdto.TopicStatus;
import com.kratisai.controlplane.api.wsdto.ActivityStatus;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.CanvasRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.repository.SandboxExecutionActivityRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatRepository chatRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final AgentChatMemoryRepository chatMemoryRepository;
    private final ChatUsageSessionRepository chatUsageSessionRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final SandboxExecutionActivityRepository activityRepository;
    private final ExecutionDiffSnapshotRepository diffSnapshotRepository;
    private final CanvasRepository canvasRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatFluxRegistry chatFluxRegistry;

    public ChatService(
            ChatRepository chatRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            TeamMemberRepository teamMemberRepository,
            AgentChatMemoryRepository chatMemoryRepository,
            ChatUsageSessionRepository chatUsageSessionRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            SandboxExecutionActivityRepository activityRepository,
            ExecutionDiffSnapshotRepository diffSnapshotRepository,
            CanvasRepository canvasRepository,
            ApplicationEventPublisher eventPublisher,
            ChatFluxRegistry chatFluxRegistry) {
        this.chatRepository = chatRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatUsageSessionRepository = chatUsageSessionRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.activityRepository = activityRepository;
        this.diffSnapshotRepository = diffSnapshotRepository;
        this.canvasRepository = canvasRepository;
        this.eventPublisher = eventPublisher;
        this.chatFluxRegistry = chatFluxRegistry;
    }

    /** Publish a CHATS team-entity event so subscribed clients refresh the sidebar. */
    @Transactional
    public void publishChatsChanged(UUID teamId) {
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CHATS));
    }

    /** Create a new chat for a user in a team. */
    @Transactional
    public ChatEntity createChat(UUID teamId, UUID userId, String title) {
        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Verify user is a member of the team
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }

        ChatEntity chat = new ChatEntity(team, user, title);
        ChatEntity saved = chatRepository.save(chat);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CHATS));
        return saved;
    }

    /** Synchronously persist a user prompt to chat memory. */
    @Transactional
    public void saveUserMessage(UUID chatId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        List<ChatMemoryEntity> existing = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);
        int nextIndex = existing.size();
        ChatMemoryEntity entity =
                new ChatMemoryEntity(chatId, nextIndex, MessageRole.USER, userMessage, null, null, null);
        chatMemoryRepository.save(entity);
        chatRepository.touchUpdatedAt(chatId, entity.getCreatedAt());
    }

    /** Persist an assistant error message so a failed turn is visible after reconnect/reload. */
    @Transactional
    public void saveAssistantErrorMessage(UUID chatId, String message, int code) {
        List<ChatMemoryEntity> existing = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);
        int nextIndex = existing.size();
        String metadata = "{\"isError\":true,\"code\":" + code + "}";
        ChatMemoryEntity entity =
                new ChatMemoryEntity(chatId, nextIndex, MessageRole.ASSISTANT, message, null, null, metadata);
        chatMemoryRepository.save(entity);
        chatRepository.touchUpdatedAt(chatId, entity.getCreatedAt());
    }

    /** Persist an interstitial paused record into chat memory. */
    @Transactional
    public void saveInterstitialMessage(UUID chatId, String status, String reason, String directive) {
        List<ChatMemoryEntity> existing = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);
        int nextIndex = existing.size();
        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("interstitial", true);
        metadataMap.put("status", status);
        if (reason != null) {
            metadataMap.put("reason", reason);
        }
        if (directive != null) {
            metadataMap.put("directive", directive);
        }
        String metadata;
        try {
            metadata = MAPPER.writeValueAsString(metadataMap);
        } catch (JsonProcessingException e) {
            metadata = "{\"interstitial\":true,\"status\":\"" + status + "\"}";
        }
        ChatMemoryEntity entity =
                new ChatMemoryEntity(chatId, nextIndex, MessageRole.ASSISTANT, "", null, null, metadata);
        chatMemoryRepository.save(entity);
        chatRepository.touchUpdatedAt(chatId, entity.getCreatedAt());
    }

    /**
     * Reads the directive from any active interstitial paused record and marks the record resolved.
     */
    @Transactional
    public Optional<String> readAndResolveInterstitialDirective(UUID chatId) {
        List<ChatMemoryEntity> entities = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);
        String directive = null;
        for (ChatMemoryEntity entity : entities) {
            String metadata = entity.getMetadata();
            if (metadata != null && !metadata.isBlank()) {
                try {
                    JsonNode node = MAPPER.readTree(metadata);
                    if (node.path("interstitial").asBoolean(false)
                            && "paused".equals(node.path("status").asText())) {
                        directive = node.path("directive").asText(null);
                        ObjectNode obj = (ObjectNode) node;
                        obj.put("status", "resolved");
                        entity.setMetadata(MAPPER.writeValueAsString(obj));
                        chatMemoryRepository.save(entity);
                    }
                } catch (JsonProcessingException ignored) {
                }
            }
        }
        return Optional.ofNullable(directive);
    }

    /** List chats for a team, optionally filtered to only the current user's chats and status. */
    public List<ChatDto> listChatsForTeam(UUID teamId, UUID currentUserId, boolean onlyMine, String status) {
        // Verify user is a member of the team
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }

        List<ChatEntity> chats;
        String normalizedStatus = status == null ? "active" : status.toLowerCase();

        if ("archived".equals(normalizedStatus)) {
            chats = onlyMine
                    ? chatRepository.findTop20ByTeamIdAndUserIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(
                            teamId, currentUserId)
                    : chatRepository.findTop20ByTeamIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(teamId);
        } else if ("all".equals(normalizedStatus)) {
            chats = onlyMine
                    ? chatRepository.findTop20ByTeamIdAndUserIdOrderByUpdatedAtDesc(teamId, currentUserId)
                    : chatRepository.findTop20ByTeamIdOrderByUpdatedAtDesc(teamId);
        } else {
            // default: active
            chats = onlyMine
                    ? chatRepository.findTop20ByTeamIdAndUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(
                            teamId, currentUserId)
                    : chatRepository.findTop20ByTeamIdAndArchivedAtIsNullOrderByUpdatedAtDesc(teamId);
        }

        if (chats.isEmpty()) {
            return List.of();
        }
        List<UUID> chatIds = chats.stream().map(ChatEntity::getId).toList();
        Map<UUID, Instant> lastMessageAt = new HashMap<>();
        for (ChatRepository.ChatLastMessage row : chatRepository.findLastMessageAtByChatIds(chatIds)) {
            lastMessageAt.put(row.getChatId(), row.getLastMessageAt());
        }
        Map<UUID, CanvasEntity> latestCanvas = new HashMap<>();
        for (CanvasEntity canvas : canvasRepository.findByChatIdInAndDeletedAtIsNull(chatIds)) {
            latestCanvas.merge(canvas.getChatId(), canvas, ChatService::latestCanvas);
        }
        Map<UUID, List<ChatUsageSession>> sessionsByChat = new HashMap<>();
        for (ChatUsageSession session : chatUsageSessionRepository.findByChatIdIn(chatIds)) {
            sessionsByChat
                    .computeIfAbsent(session.getChat().getId(), ignored -> new ArrayList<>())
                    .add(session);
        }
        Map<UUID, SandboxExecution> latestByChat = new HashMap<>();
        for (SandboxExecution execution : sandboxExecutionRepository.findLatestByChatIds(chatIds)) {
            latestByChat.merge(execution.getChat().getId(), execution, ChatService::latestExecution);
        }
        Map<UUID, ExecutionDiffSnapshot> snapshotsByExecution = new HashMap<>();
        List<UUID> latestExecutionIds =
                latestByChat.values().stream().map(SandboxExecution::getId).toList();
        Set<UUID> pendingHitlExecutionIds = Set.of();
        if (!latestExecutionIds.isEmpty()) {
            for (ExecutionDiffSnapshot snapshot : diffSnapshotRepository.findByExecutionIdIn(latestExecutionIds)) {
                snapshotsByExecution.put(snapshot.getExecutionId(), snapshot);
            }
            pendingHitlExecutionIds = new HashSet<>(
                    activityRepository.findExecutionIdsWithStatus(latestExecutionIds, ActivityStatus.PENDING));
        }

        Set<UUID> blocked = pendingHitlExecutionIds;
        return chats.stream()
                .map(chat -> {
                    SandboxExecution latest = latestByChat.get(chat.getId());
                    ExecutionDiffSnapshot snapshot = latest != null ? snapshotsByExecution.get(latest.getId()) : null;
                    List<ChatUsageSession> sessions = sessionsByChat.getOrDefault(chat.getId(), List.of());
                    return toChatDto(
                            chat,
                            aggregateUsage(sessions),
                            latest,
                            snapshot,
                            lastMessageAt.get(chat.getId()),
                            latestCanvas.get(chat.getId()),
                            latest != null && blocked.contains(latest.getId()));
                })
                .toList();
    }

    private ChatDto toChatDto(
            ChatEntity chat,
            ChatUsageDto usage,
            SandboxExecution latest,
            ExecutionDiffSnapshot snapshot,
            Instant lastMessageAt,
            CanvasEntity latestCanvas,
            boolean pendingHitl) {
        LastActivity lastActivity = deriveLastActivity(chat, latest, snapshot, lastMessageAt, latestCanvas);
        return new ChatDto(
                chat.getId(),
                chat.getTeam().getId(),
                chat.getTitle(),
                chat.getUser().getDisplayName(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                chat.getArchivedAt(),
                usage.totalSpend(),
                usage.totalTokens(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.usageLastUpdatedAt(),
                chat.isAllowAllPermissions(),
                deriveStatus(chat, latest, snapshot, pendingHitl),
                lastActivity.at(),
                lastActivity.type(),
                lastActivity.targetId());
    }

    private static CanvasEntity latestCanvas(CanvasEntity first, CanvasEntity second) {
        return first.getUpdatedAt().isAfter(second.getUpdatedAt()) ? first : second;
    }

    private static SandboxExecution latestExecution(SandboxExecution first, SandboxExecution second) {
        return first.getStartedAt().isAfter(second.getStartedAt()) ? first : second;
    }

    @Transactional(readOnly = true)
    public ChatUsageDto getChatUsage(UUID chatId, UUID teamId, UUID userId) {
        requireChatAccess(chatId, teamId, userId);
        return aggregateUsage(chatId);
    }

    @Transactional(readOnly = true)
    public ChatDto getChat(UUID chatId, UUID teamId, UUID userId) {
        ChatEntity chat = requireChatAccess(chatId, teamId, userId);
        SandboxExecution latest = sandboxExecutionRepository
                .findFirstByChatIdOrderByStartedAtDesc(chatId)
                .orElse(null);
        ExecutionDiffSnapshot snapshot = latest != null
                ? diffSnapshotRepository.findByExecutionId(latest.getId()).orElse(null)
                : null;
        CanvasEntity latestCanvas = canvasRepository.findByChatIdAndDeletedAtIsNull(chatId).stream()
                .max((first, second) -> first.getUpdatedAt().compareTo(second.getUpdatedAt()))
                .orElse(null);
        boolean pendingHitl = latest != null
                && activityRepository.existsByExecutionIdAndStatus(latest.getId(), ActivityStatus.PENDING);
        return toChatDto(
                chat,
                aggregateUsage(chatId),
                latest,
                snapshot,
                chatRepository.findLastMessageAt(chatId).orElse(null),
                latestCanvas,
                pendingHitl);
    }

    private ChatEntity requireChatAccess(UUID chatId, UUID teamId, UUID userId) {
        ChatEntity chat = chatRepository
                .findByIdAndTeamId(chatId, teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
        return chat;
    }

    private ChatUsageDto aggregateUsage(UUID chatId) {
        return aggregateUsage(chatUsageSessionRepository.findByChatId(chatId));
    }

    private ChatUsageDto aggregateUsage(List<ChatUsageSession> sessions) {
        double totalSpend = sessions.stream()
                .mapToDouble(sess -> sess.getUsage().getTotalSpend() != null
                        ? sess.getUsage().getTotalSpend()
                        : 0.0)
                .sum();
        long totalTokens = sessions.stream()
                .mapToLong(sess -> sess.getUsage().getTotalTokens() != null
                        ? sess.getUsage().getTotalTokens()
                        : 0L)
                .sum();
        long promptTokens = sessions.stream()
                .mapToLong(sess -> sess.getUsage().getPromptTokens() != null
                        ? sess.getUsage().getPromptTokens()
                        : 0L)
                .sum();
        long completionTokens = sessions.stream()
                .mapToLong(sess -> sess.getUsage().getCompletionTokens() != null
                        ? sess.getUsage().getCompletionTokens()
                        : 0L)
                .sum();
        Instant usageLastUpdatedAt = sessions.stream()
                .map(sess -> sess.getUsage().getUsageLastUpdatedAt())
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return new ChatUsageDto(totalSpend, totalTokens, promptTokens, completionTokens, usageLastUpdatedAt);
    }

    private TopicStatus deriveStatus(
            ChatEntity chat, SandboxExecution latest, ExecutionDiffSnapshot snapshot, boolean pendingHitl) {
        if (chatFluxRegistry.containsKey(chat.getId())) {
            return TopicStatus.PLANNING;
        }
        if (latest == null) {
            return TopicStatus.IDLE;
        }
        if (pendingHitl) {
            return TopicStatus.BLOCKED;
        }
        if (latest.getStatus() == SandboxExecutionStatus.RUNNING) {
            return TopicStatus.WORKING;
        }
        if (latest.getStatus() == SandboxExecutionStatus.FAILED) {
            return TopicStatus.FAILED;
        }
        var latestChangeSet = latest.getLatestChangeSet();
        if (snapshot != null
                && (snapshot.getTotalAdditions() > 0 || snapshot.getTotalDeletions() > 0)
                && (latestChangeSet == null
                        || !Objects.equals(snapshot.getManifestDigest(), latestChangeSet.getManifestDigest()))) {
            return TopicStatus.READY_TO_PUBLISH;
        }
        if (latestChangeSet != null
                && (snapshot == null
                        || Objects.equals(snapshot.getManifestDigest(), latestChangeSet.getManifestDigest()))
                && latestChangeSet.getPrStatus() != PublishedPrStatus.MERGED
                && latestChangeSet.getPrStatus() != PublishedPrStatus.CLOSED) {
            return TopicStatus.MR_PUSHED;
        }
        return TopicStatus.IDLE;
    }

    private LastActivity deriveLastActivity(
            ChatEntity chat,
            SandboxExecution latest,
            ExecutionDiffSnapshot snapshot,
            Instant lastMessageAt,
            CanvasEntity latestCanvas) {
        Instant chatAt = lastMessageAt;
        Instant executionAt = latest != null
                ? latestInstant(
                        latestInstant(latest.getStartedAt(), latest.getCompletedAt()),
                        snapshot != null ? snapshot.getUpdatedAt() : null)
                : null;
        Instant canvasAt = latestCanvas != null ? latestCanvas.getUpdatedAt() : null;

        if (executionAt != null && !isBefore(executionAt, chatAt) && !isBefore(executionAt, canvasAt)) {
            return new LastActivity(
                    executionAt, LastActivityType.EXECUTION, latest.getId().toString());
        }
        if (canvasAt != null && !isBefore(canvasAt, chatAt)) {
            return new LastActivity(canvasAt, LastActivityType.CANVAS, latestCanvas.getDocumentId());
        }
        if (chatAt != null) {
            return new LastActivity(chatAt, LastActivityType.CHAT, chat.getId().toString());
        }
        return new LastActivity(
                chat.getUpdatedAt(), LastActivityType.CHAT, chat.getId().toString());
    }

    private static boolean isBefore(Instant candidate, Instant reference) {
        return reference != null && candidate.isBefore(reference);
    }

    private record LastActivity(Instant at, LastActivityType type, String targetId) {}

    private Instant latestInstant(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    /** Archive a chat. */
    @Transactional
    public void archiveChat(UUID chatId, UUID userId) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        if (!teamMemberRepository.existsByTeamIdAndUserId(chat.getTeam().getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }

        chatRepository.updateArchivedAt(chatId, Instant.now());
        eventPublisher.publishEvent(new TeamEntityChangedEvent(chat.getTeam().getId(), TeamEntityType.CHATS));
    }

    /** Unarchive a chat. */
    @Transactional
    public void unarchiveChat(UUID chatId, UUID userId) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        if (!teamMemberRepository.existsByTeamIdAndUserId(chat.getTeam().getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }

        chatRepository.updateArchivedAt(chatId, null);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(chat.getTeam().getId(), TeamEntityType.CHATS));
    }

    @Transactional
    public void setAllowAllPermissions(UUID chatId, UUID userId, boolean enabled) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));

        if (!teamMemberRepository.existsByTeamIdAndUserId(chat.getTeam().getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }

        chatRepository.updateAllowAllPermissions(chatId, enabled);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(chat.getTeam().getId(), TeamEntityType.CHATS));
    }

    /** Verify that a chat exists and belongs to the given team. Callers enforce team membership. */
    public void verifyChat(UUID chatId, UUID teamId) {
        chatRepository
                .findByIdAndTeamId(chatId, teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
    }

    /** Returns the current title of a chat, or empty if the chat does not exist. */
    @Transactional(readOnly = true)
    public Optional<String> findTitle(UUID chatId) {
        return chatRepository.findById(chatId).map(ChatEntity::getTitle);
    }

    /** Updates title without touching {@code updated_at}. Publishes {@link TeamEntityChangedEvent}. */
    @Transactional
    public boolean updateTitle(UUID chatId, UUID teamId, String title) {
        if (chatRepository.updateTitle(chatId, title) <= 0) {
            return false;
        }
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CHATS));
        return true;
    }
}
