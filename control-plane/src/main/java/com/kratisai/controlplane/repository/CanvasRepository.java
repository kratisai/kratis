package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.CanvasEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanvasRepository extends JpaRepository<CanvasEntity, Long> {

    List<CanvasEntity> findByChatIdAndDeletedAtIsNull(UUID chatId);

    Optional<CanvasEntity> findByChatIdAndDocumentIdAndDeletedAtIsNull(UUID chatId, String documentId);

    /**
     * Unfiltered lookup used where tombstones must be visible: soft-delete resolution and blocking
     * re-creation of a deleted {@code documentId}. Reads through the service use the filtered
     * variants above so soft-deleted documents stay excluded from every canvas operation.
     */
    Optional<CanvasEntity> findByChatIdAndDocumentId(UUID chatId, String documentId);
}
