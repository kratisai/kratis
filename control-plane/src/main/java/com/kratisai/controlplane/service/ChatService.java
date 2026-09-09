package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.ChatDto;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

    private final ChatRepository chatRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final AgentChatMemoryRepository chatMemoryRepository;
    private final ChatUsageSessionRepository chatUsageSessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ChatService(
            ChatRepository chatRepository,
            TeamRepository teamRepository,
            UserRepository userRepository,
            TeamMemberRepository teamMemberRepository,
            AgentChatMemoryRepository chatMemoryRepository,
            ChatUsageSessionRepository chatUsageSessionRepository,
            ApplicationEventPublisher eventPublisher) {
        this.chatRepository = chatRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatUsageSessionRepository = chatUsageSessionRepository;
        this.eventPublisher = eventPublisher;
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

        return chats.stream().map(this::toChatDto).toList();
    }

    private ChatDto toChatDto(ChatEntity chat) {
        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatId(chat.getId());
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

        return new ChatDto(
                chat.getId(),
                chat.getTeam().getId(),
                chat.getTitle(),
                chat.getUser().getDisplayName(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                chat.getArchivedAt(),
                totalSpend,
                totalTokens,
                promptTokens,
                completionTokens,
                usageLastUpdatedAt);
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

    /** Verify that a chat exists and belongs to the given team. Returns the chat if valid. */
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
