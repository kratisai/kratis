package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.restdto.ChatUsageDto;
import com.kratisai.controlplane.api.restdto.LastActivityType;
import com.kratisai.controlplane.api.restdto.TopicStatus;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChangeSet;
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
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
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

        String normalizedStatus = status == null ? "active" : status.toLowerCase();
        Boolean archived =
                switch (normalizedStatus) {
                    case "archived" -> true;
                    case "all" -> null;
                    default -> false;
                };
        UUID filteredUserId = onlyMine ? currentUserId : null;
        List<ChatRepository.RecentChatRow> rows =
                chatRepository.findRecentByLastActivity(teamId, filteredUserId, archived, 20);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> chatIds =
                rows.stream().map(ChatRepository.RecentChatRow::getId).toList();
        Map<UUID, CanvasRepository.CanvasActivity> latestCanvas = new HashMap<>();
        for (CanvasRepository.CanvasActivity canvas : canvasRepository.findActiveMetadataByChatIdIn(chatIds)) {
            latestCanvas.merge(canvas.getChatId(), canvas, ChatService::latestCanvas);
        }
        List<UUID> latestExecutionIds = rows.stream()
                .map(ChatRepository.RecentChatRow::getLatestExecutionId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, SandboxExecution> executionsById = new HashMap<>();
        Map<UUID, ExecutionDiffSnapshot> snapshotsByExecution = new HashMap<>();
        if (!latestExecutionIds.isEmpty()) {
            for (SandboxExecution execution : sandboxExecutionRepository.findAllById(latestExecutionIds)) {
                executionsById.put(execution.getId(), execution);
            }
            for (ExecutionDiffSnapshot snapshot : diffSnapshotRepository.findByExecutionIdIn(latestExecutionIds)) {
                snapshotsByExecution.put(snapshot.getExecutionId(), snapshot);
            }
        }
        return rows.stream()
                .map(row -> {
                    SandboxExecution latest =
                            row.getLatestExecutionId() != null ? executionsById.get(row.getLatestExecutionId()) : null;
                    ExecutionDiffSnapshot snapshot = latest != null ? snapshotsByExecution.get(latest.getId()) : null;
                    return toChatDto(row, latest, snapshot, latestCanvas.get(row.getId()));
                })
                .sorted(Comparator.comparing(ChatDto::lastActivityAt, Comparator.reverseOrder()))
                .toList();
    }

    private ChatDto toChatDto(
            ChatRepository.RecentChatRow row,
            SandboxExecution latest,
            ExecutionDiffSnapshot snapshot,
            CanvasRepository.CanvasActivity latestCanvas) {
        LastActivity lastActivity = deriveLastActivity(row, latest, snapshot, latestCanvas);
        return new ChatDto(
                row.getId(),
                row.getTitle(),
                row.isAllowAllPermissions(),
                deriveStatus(row, latest, snapshot, latestCanvas),
                lastActivity.at(),
                lastActivity.type(),
                lastActivity.targetId());
    }

    private static CanvasRepository.CanvasActivity latestCanvas(
            CanvasRepository.CanvasActivity first, CanvasRepository.CanvasActivity second) {
        return first.getUpdatedAt().isAfter(second.getUpdatedAt()) ? first : second;
    }

    @Transactional(readOnly = true)
    public ChatUsageDto getChatUsage(UUID chatId, UUID teamId, UUID userId) {
        requireChatAccess(chatId, teamId, userId);
        return aggregateUsage(chatId);
    }

    @Transactional(readOnly = true)
    public ChatDto getChat(UUID chatId, UUID teamId, UUID userId) {
        ChatRepository.RecentChatRow row = chatRepository
                .findSummaryByIdAndTeamId(chatId, teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
        SandboxExecution latest = row.getLatestExecutionId() != null
                ? sandboxExecutionRepository
                        .findById(row.getLatestExecutionId())
                        .orElse(null)
                : null;
        ExecutionDiffSnapshot snapshot = latest != null
                ? diffSnapshotRepository.findByExecutionId(latest.getId()).orElse(null)
                : null;
        CanvasRepository.CanvasActivity latestCanvas =
                canvasRepository.findActiveMetadataByChatIdIn(List.of(chatId)).stream()
                        .max(Comparator.comparing(CanvasRepository.CanvasActivity::getUpdatedAt))
                        .orElse(null);
        return toChatDto(row, latest, snapshot, latestCanvas);
    }

    private void requireChatAccess(UUID chatId, UUID teamId, UUID userId) {
        chatRepository
                .findByIdAndTeamId(chatId, teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
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
            ChatRepository.RecentChatRow row,
            SandboxExecution latest,
            ExecutionDiffSnapshot snapshot,
            CanvasRepository.CanvasActivity latestCanvas) {
        if (row.isPendingHitl()) {
            return TopicStatus.BLOCKED;
        }
        if (chatFluxRegistry.containsKey(row.getId())) {
            return TopicStatus.PLANNING;
        }
        if (latest == null) {
            return TopicStatus.IDLE;
        }
        if (latest.getStatus() == SandboxExecutionStatus.RUNNING) {
            return TopicStatus.WORKING;
        }
        var latestChangeSet = latest.getLatestChangeSet();
        boolean changeSetCoversSnapshot = changeSetCoversSnapshot(latestChangeSet, snapshot);
        if (snapshot != null
                && (snapshot.getTotalAdditions() > 0 || snapshot.getTotalDeletions() > 0)
                && !changeSetCoversSnapshot) {
            return TopicStatus.READY_TO_PUBLISH;
        }
        if (latestChangeSet != null && changeSetCoversSnapshot) {
            if (latestChangeSet.getPrStatus() == PublishedPrStatus.MERGED) {
                Instant mergedAt = latestChangeSet.getPrMergedAt() != null
                        ? latestChangeSet.getPrMergedAt()
                        : latestChangeSet.getCreatedAt();
                return hasActivityAfter(mergedAt, row.getLastMessageAt(), latestCanvas, latest)
                        ? TopicStatus.IDLE
                        : TopicStatus.MR_MERGED;
            }
            if (latestChangeSet.getPrStatus() != PublishedPrStatus.CLOSED) {
                return hasActivityAfter(latestChangeSet.getCreatedAt(), row.getLastMessageAt(), latestCanvas, latest)
                        ? TopicStatus.IDLE
                        : TopicStatus.MR_PUSHED;
            }
        }
        return TopicStatus.IDLE;
    }

    private static boolean changeSetCoversSnapshot(ChangeSet changeSet, ExecutionDiffSnapshot snapshot) {
        if (changeSet == null) {
            return false;
        }
        // Change sets written before manifest digests were tracked cannot be compared with the
        // current snapshot. Treat them as matching so a historical publish is not reported as
        // unpublished work.
        if (changeSet.getManifestDigest() == null || snapshot == null) {
            return true;
        }
        if (Objects.equals(snapshot.getManifestDigest(), changeSet.getManifestDigest())) {
            return true;
        }
        // Older publishes stored the sidecar change-set stats digest instead of the diff manifest
        // digest. Compare the equivalent stats digest so those rows are not stuck showing
        // "Ready to publish".
        return Objects.equals(changeSetStatsDigest(snapshot.getSummaryJson()), changeSet.getManifestDigest());
    }

    private static String changeSetStatsDigest(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return null;
        }
        try {
            JsonNode files = MAPPER.readTree(summaryJson);
            if (files == null || !files.isArray()) {
                return null;
            }
            List<JsonNode> sortedFiles = new ArrayList<>();
            files.forEach(sortedFiles::add);
            sortedFiles.sort(Comparator.comparing(file -> file.path("path").asText()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (JsonNode file : sortedFiles) {
                String line = file.path("path").asText()
                        + ":" + file.path("status").asText()
                        + ":" + file.path("additions").asInt()
                        + ":" + file.path("deletions").asInt()
                        + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private boolean hasActivityAfter(
            Instant reference,
            Instant lastMessageAt,
            CanvasRepository.CanvasActivity latestCanvas,
            SandboxExecution latest) {
        Instant lastOtherActivity = latestInstant(
                latestInstant(lastMessageAt, latestCanvas != null ? latestCanvas.getUpdatedAt() : null),
                latest.getStartedAt());
        return reference != null && lastOtherActivity != null && lastOtherActivity.isAfter(reference);
    }

    private LastActivity deriveLastActivity(
            ChatRepository.RecentChatRow row,
            SandboxExecution latest,
            ExecutionDiffSnapshot snapshot,
            CanvasRepository.CanvasActivity latestCanvas) {
        Instant publishAt = latest != null && latest.getLatestChangeSet() != null
                ? latest.getLatestChangeSet().getCreatedAt()
                : null;
        Instant executionAt = latest != null
                ? latestInstant(
                        latestInstant(latest.getStartedAt(), row.getLatestExecutionActivityAt()),
                        latestInstant(publishAt, snapshot != null ? snapshot.getUpdatedAt() : null))
                : null;
        Instant canvasAt = latestCanvas != null ? latestCanvas.getUpdatedAt() : null;
        Instant lastMessageAt = row.getLastMessageAt();

        if (executionAt != null && !isBefore(executionAt, lastMessageAt) && !isBefore(executionAt, canvasAt)) {
            return new LastActivity(
                    executionAt, LastActivityType.EXECUTION, latest.getId().toString());
        }
        if (canvasAt != null && !isBefore(canvasAt, lastMessageAt)) {
            return new LastActivity(canvasAt, LastActivityType.CANVAS, latestCanvas.getDocumentId());
        }
        if (lastMessageAt != null) {
            return new LastActivity(
                    lastMessageAt, LastActivityType.CHAT, row.getId().toString());
        }
        return new LastActivity(
                row.getUpdatedAt(), LastActivityType.CHAT, row.getId().toString());
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
