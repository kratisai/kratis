package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatUsageSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatUsageSessionService.class);
    private static final Duration INACTIVITY_THRESHOLD = Duration.ofHours(4);

    private final ChatUsageSessionRepository chatUsageSessionRepository;
    private final ChatRepository chatRepository;
    private final VirtualKeyService virtualKeyService;

    @Autowired
    public ChatUsageSessionService(
            ChatUsageSessionRepository chatUsageSessionRepository,
            ChatRepository chatRepository,
            VirtualKeyService virtualKeyService) {
        this.chatUsageSessionRepository = chatUsageSessionRepository;
        this.chatRepository = chatRepository;
        this.virtualKeyService = virtualKeyService;
    }

    public ChatUsageSessionService(
            ChatUsageSessionRepository chatUsageSessionRepository, VirtualKeyService virtualKeyService) {
        this(chatUsageSessionRepository, null, virtualKeyService);
    }

    @Transactional
    public ChatUsageSession getOrCreateActiveSession(ChatEntity chat, List<String> modelNames) {
        String requestedModel = modelNames != null && !modelNames.isEmpty() ? modelNames.getFirst() : null;
        return getOrCreateActiveSession(chat, requestedModel, requestedModel);
    }

    @Transactional
    public ChatUsageSession getOrCreateActiveSession(ChatEntity chat, String requestedModel, String litellmModelAlias) {
        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(chat.getId());
        Instant now = Instant.now();
        if (!sessions.isEmpty()) {
            ChatUsageSession latest = sessions.getFirst();
            if (!latest.isEnded()) {
                Instant referenceTime = latest.getUsage().getUsageLastUpdatedAt() != null
                        ? latest.getUsage().getUsageLastUpdatedAt()
                        : latest.getStartedAt();
                boolean withinThreshold = Duration.between(referenceTime, now).compareTo(INACTIVITY_THRESHOLD) <= 0;
                boolean keyValid = isVirtualKeyValid(latest.getUsage().getVirtualKey());
                boolean sameModel = requestedModel == null || Objects.equals(latest.getModel(), requestedModel);

                if (withinThreshold && keyValid && sameModel) {
                    if (latest.getModel() == null && requestedModel != null) {
                        latest.setModel(requestedModel);
                        chatUsageSessionRepository.save(latest);
                    }
                    return latest;
                }

                latest.setEndedAt(now);
                if (latest.getUsage().getVirtualKey() != null) {
                    try {
                        LlmUsageSnapshot snapshot =
                                virtualKeyService.fetchUsage(latest.getUsage().getVirtualKey());
                        latest.getUsage().apply(snapshot);
                        virtualKeyService.revokeKey(latest.getUsage().getVirtualKey());
                    } catch (Exception e) {
                        logger.warn("Failed to fetch usage or revoke stale virtual key for chat {}", chat.getId(), e);
                    }
                }
                chatUsageSessionRepository.save(latest);
            }
        }
        String keyAlias = "kratis-chat-" + chat.getId();
        String virtualKey = null;
        String modelForKey = litellmModelAlias != null ? litellmModelAlias : requestedModel;
        List<String> models = modelForKey != null ? List.of(modelForKey) : List.of();
        try {
            virtualKey = virtualKeyService.generateKey(keyAlias, models);
        } catch (Exception e) {
            logger.error("Failed to generate LiteLLM virtual key for chat session {}", chat.getId(), e);
        }
        ChatUsageSession newSession = new ChatUsageSession(chat, virtualKey, requestedModel);
        return chatUsageSessionRepository.save(newSession);
    }

    @Transactional
    public ChatUsageSession getOrCreateActiveSession(UUID sessionId, String modelName, String litellmModelAlias) {
        ChatEntity chat = null;
        boolean chatExists = false;
        if (chatRepository != null) {
            chat = chatRepository.findById(sessionId).orElse(null);
            chatExists = chat != null;
        }
        if (chat == null) {
            chat = new ChatEntity();
            chat.setId(sessionId);
        }
        String modelForKey = litellmModelAlias != null ? litellmModelAlias : modelName;
        List<String> models = modelForKey != null ? List.of(modelForKey) : List.of();
        if (!chatExists) {
            String keyAlias = "kratis-chat-" + chat.getId();
            String virtualKey = null;
            try {
                virtualKey = virtualKeyService.generateKey(keyAlias, models);
            } catch (Exception e) {
                logger.error("Failed to generate LiteLLM virtual key for chat session {}", chat.getId(), e);
            }
            return new ChatUsageSession(chat, virtualKey, modelName);
        }
        return getOrCreateActiveSession(chat, modelName, litellmModelAlias);
    }

    @Transactional
    public void recordUsage(UUID sessionId, LlmUsageSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatIdOrderByStartedAtDesc(sessionId);
        Optional<ChatUsageSession> target =
                sessions.stream().filter(s -> !s.isEnded()).findFirst();
        if (target.isEmpty() && !sessions.isEmpty()) {
            target = Optional.of(sessions.getFirst());
        }
        target.ifPresent(session -> {
            session.getUsage().apply(snapshot);
            chatUsageSessionRepository.save(session);
        });
    }

    @Transactional
    public void syncUsage(UUID sessionId, String virtualKey) {
        if (virtualKey == null || virtualKey.isBlank()) {
            return;
        }
        try {
            LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(virtualKey);
            Optional<ChatUsageSession> byKey = chatUsageSessionRepository.findByVirtualKey(virtualKey);
            if (byKey.isPresent()) {
                byKey.get().getUsage().apply(snapshot);
                chatUsageSessionRepository.save(byKey.get());
                return;
            }
            recordUsage(sessionId, snapshot);
        } catch (Exception e) {
            logger.warn("Failed to sync usage for session {} virtualKey {}", sessionId, virtualKey, e);
        }
    }

    private boolean isVirtualKeyValid(String virtualKey) {
        if (virtualKey == null || virtualKey.isBlank()) {
            return false;
        }
        try {
            virtualKeyService.fetchUsage(virtualKey);
            return true;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("not found")
                    || msg.contains("no key")
                    || msg.contains("404")
                    || msg.contains("401")
                    || msg.contains("expired")
                    || msg.contains("invalid")) {
                logger.warn("Virtual key appears invalid or expired, will roll over session", e);
                return false;
            }
            logger.warn("Failed to validate virtual key, treating as invalid for rollover", e);
            return false;
        }
    }

    @Transactional
    public void refreshUsageForChat(UUID chatId) {
        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatId(chatId);
        for (ChatUsageSession session : sessions) {
            if (session.getUsage().getVirtualKey() != null && !session.isEnded()) {
                try {
                    LlmUsageSnapshot snapshot =
                            virtualKeyService.fetchUsage(session.getUsage().getVirtualKey());
                    session.getUsage().apply(snapshot);
                    chatUsageSessionRepository.save(session);
                } catch (Exception e) {
                    logger.warn("Failed to fetch usage for virtual key in session {}", session.getId(), e);
                }
            }
        }
    }

    @Transactional
    public void closeAndRevokeSessions(UUID chatId) {
        List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatId(chatId);
        Instant now = Instant.now();
        for (ChatUsageSession session : sessions) {
            if (!session.isEnded()) {
                session.setEndedAt(now);
                if (session.getUsage().getVirtualKey() != null) {
                    try {
                        LlmUsageSnapshot snapshot =
                                virtualKeyService.fetchUsage(session.getUsage().getVirtualKey());
                        session.getUsage().apply(snapshot);
                        virtualKeyService.revokeKey(session.getUsage().getVirtualKey());
                    } catch (Exception e) {
                        logger.warn(
                                "Failed to finalize usage or revoke key on close for session {}", session.getId(), e);
                    }
                }
                chatUsageSessionRepository.save(session);
            }
        }
    }
}
