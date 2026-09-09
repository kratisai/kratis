package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.event.CanvasDeletedEvent;
import com.kratisai.controlplane.repository.CanvasRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Service for managing canvas documents per chat. */
@Service
public class CanvasService {

    private final CanvasRepository canvasRepository;
    private final ChatRepository chatRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CanvasService(
            CanvasRepository canvasRepository,
            ChatRepository chatRepository,
            TeamMemberRepository teamMemberRepository,
            ApplicationEventPublisher eventPublisher) {
        this.canvasRepository = canvasRepository;
        this.chatRepository = chatRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CanvasEntity createCanvas(
            UUID chatId,
            String documentId,
            String title,
            String content,
            CanvasType canvasType,
            @Nullable Repository repository,
            @Nullable String newRepoName) {
        Objects.requireNonNull(documentId, "documentId is required");
        Optional<CanvasEntity> existing = canvasRepository.findByChatIdAndDocumentId(chatId, documentId);
        if (existing.isPresent() && existing.get().getDeletedAt() != null) {
            throw new IllegalArgumentException("Canvas document '" + documentId
                    + "' was deleted and cannot be recreated; use a different document id");
        }
        validateRepoForm(canvasType, repository, newRepoName);
        CanvasEntity entity = new CanvasEntity(chatId, documentId, title, content);
        entity.setCanvasType(canvasType);
        entity.setRepository(repository);
        entity.setNewRepoName(newRepoName);
        return initializeRepoLabel(canvasRepository.save(entity));
    }

    /**
     * Update an existing canvas document's content only; type and repository association are
     * preserved.
     */
    @Transactional
    public CanvasEntity updateCanvas(UUID chatId, String documentId, String content) {
        CanvasEntity entity = findCanvasOrThrow(chatId, documentId);
        entity.setContent(content);
        return initializeRepoLabel(canvasRepository.save(entity));
    }

    /**
     * Fully overwrite an existing canvas document including its type and repository association.
     */
    @Transactional
    public CanvasEntity updateCanvas(
            UUID chatId,
            String documentId,
            String title,
            String content,
            CanvasType canvasType,
            @Nullable Repository repository,
            @Nullable String newRepoName) {
        validateRepoForm(canvasType, repository, newRepoName);
        CanvasEntity entity = findCanvasOrThrow(chatId, documentId);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setCanvasType(canvasType);
        entity.setRepository(repository);
        entity.setNewRepoName(newRepoName);
        return initializeRepoLabel(canvasRepository.save(entity));
    }

    /** Get all canvas documents for a chat, excluding soft-deleted documents. */
    @Transactional(readOnly = true)
    public List<CanvasEntity> getCanvasesForChat(UUID chatId) {
        return canvasRepository.findByChatIdAndDeletedAtIsNull(chatId).stream()
                .map(this::initializeRepoLabel)
                .toList();
    }

    /** Get a specific canvas document, or {@code null} when absent or soft-deleted. */
    @Nullable
    @Transactional(readOnly = true)
    public CanvasEntity getCanvas(UUID chatId, String documentId) {
        return canvasRepository
                .findByChatIdAndDocumentIdAndDeletedAtIsNull(chatId, documentId)
                .map(this::initializeRepoLabel)
                .orElse(null);
    }

    /**
     * Soft-delete a canvas document. Idempotent: deleting an already-soft-deleted document is a
     * no-op. Publishes {@link CanvasDeletedEvent} so subscribers can drop the document from their
     * live view.
     */
    @Transactional
    public void deleteCanvas(UUID userId, UUID chatId, String documentId) {
        ChatEntity chat = chatRepository
                .findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
        requireTeamMembership(userId, chat.getTeam().getId());
        CanvasEntity entity = canvasRepository
                .findByChatIdAndDocumentId(chatId, documentId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Canvas document not found: " + documentId));
        if (entity.getDeletedAt() == null) {
            entity.setDeletedAt(Instant.now());
            canvasRepository.save(entity);
            eventPublisher.publishEvent(new CanvasDeletedEvent(chat.getTeam().getId(), chatId, documentId));
        }
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private CanvasEntity findCanvasOrThrow(UUID chatId, String documentId) {
        return canvasRepository
                .findByChatIdAndDocumentIdAndDeletedAtIsNull(chatId, documentId)
                .orElseThrow(() -> new IllegalArgumentException("Canvas not found: " + documentId));
    }

    private void validateRepoForm(
            CanvasType canvasType, @Nullable Repository repository, @Nullable String newRepoName) {
        Objects.requireNonNull(canvasType, "canvasType is required");
        boolean hasRepository = repository != null;
        boolean hasNewRepo = newRepoName != null && !newRepoName.isBlank();
        if (canvasType == CanvasType.SPEC) {
            if (hasRepository && hasNewRepo) {
                throw new IllegalArgumentException(
                        "spec canvas requires exactly one of an existing repository or a new-repository name");
            }
            if (!hasRepository && !hasNewRepo) {
                throw new IllegalArgumentException(
                        "spec canvas requires a repository name or a new-repository definition");
            }
        } else if (hasRepository || hasNewRepo) {
            throw new IllegalArgumentException("document canvas must not define a repository");
        }
    }

    // Getter call intentionally forces the lazy repository association to load inside the
    // session so detached consumers can call getRepoLabel() without a LazyInitializationException.
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"})
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private CanvasEntity initializeRepoLabel(CanvasEntity entity) {
        if (entity.getRepository() != null) {
            entity.getRepository().getName();
        }
        return entity;
    }

    /** Validate markdown content. Returns true if valid, false otherwise. */
    public boolean validateMarkdown(String content) {
        // Basic validation: check for unclosed HTML tags, malformed markdown
        // Can be enhanced with flexmark-java in Phase 2
        if (content == null || content.isEmpty()) {
            return false;
        }
        // Check for balanced code block fences
        long fenceCount = content.chars().filter(ch -> ch == '`').count();
        return fenceCount % 2 == 0;
    }

    /** Exception thrown when optimistic locking fails. */
    public static class OptimisticLockingException extends RuntimeException {
        public OptimisticLockingException(String message) {
            super(message);
        }
    }
}
