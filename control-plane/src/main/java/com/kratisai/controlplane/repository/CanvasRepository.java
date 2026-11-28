package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CanvasEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanvasRepository extends JpaRepository<CanvasEntity, Long> {

    List<CanvasEntity> findByChatIdAndDeletedAtIsNull(UUID chatId);

    Optional<CanvasEntity> findByChatIdAndDocumentIdAndDeletedAtIsNull(UUID chatId, String documentId);

    @Query("SELECT cv.chatId AS chatId, cv.documentId AS documentId, cv.updatedAt AS updatedAt"
            + " FROM CanvasEntity cv WHERE cv.chatId IN :chatIds AND cv.deletedAt IS NULL")
    List<CanvasActivity> findActiveMetadataByChatIdIn(@Param("chatIds") Collection<UUID> chatIds);

    interface CanvasActivity {
        UUID getChatId();

        String getDocumentId();

        Instant getUpdatedAt();
    }

    /**
     * Unfiltered lookup used where tombstones must be visible: soft-delete resolution and blocking
     * re-creation of a deleted {@code documentId}. Reads through the service use the filtered
     * variants above so soft-deleted documents stay excluded from every canvas operation.
     */
    Optional<CanvasEntity> findByChatIdAndDocumentId(UUID chatId, String documentId);
}
