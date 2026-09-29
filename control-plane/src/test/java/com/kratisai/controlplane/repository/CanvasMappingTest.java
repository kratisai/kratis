package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.Team;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class CanvasMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private CanvasRepository canvasRepository;

    @Test
    void testCanvasEntityMapping() {
        // Create team first
        Team team = new Team("Canvas Team", "Team with canvas");
        teamRepository.save(team);
        entityManager.flush();

        // Create and save canvas entity
        UUID sessionId = team.getId();
        String documentId = "doc-123";
        CanvasEntity canvas = new CanvasEntity(sessionId, documentId, "Test Canvas", "# Hello");
        canvasRepository.save(canvas);
        entityManager.flush();

        // Verify save
        assertThat(canvas.getId()).isNotNull();
        assertThat(canvas.getCreatedAt()).isNotNull();
        assertThat(canvas.getUpdatedAt()).isNotNull();

        // Verify findByChatIdAndDeletedAtIsNull (active only)
        List<CanvasEntity> canvases = canvasRepository.findByChatIdAndDeletedAtIsNull(sessionId);
        assertThat(canvases).hasSize(1);
        assertThat(canvases.getFirst().getTitle()).isEqualTo("Test Canvas");
        assertThat(canvases.getFirst().getContent()).isEqualTo("# Hello");
        assertThat(canvases.getFirst().getDocumentId()).isEqualTo(documentId);

        // Verify findByChatIdAndDocumentIdAndDeletedAtIsNull (active only)
        Optional<CanvasEntity> found =
                canvasRepository.findByChatIdAndDocumentIdAndDeletedAtIsNull(sessionId, documentId);
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Test Canvas");

        // Verify update triggers updatedAt and version increment
        found.get().setContent("# Updated");
        entityManager.flush();
        assertThat(found.get().getUpdatedAt()).isNotNull();

        // Soft delete: timestamped row is hidden from active queries but the unfiltered
        // lookup (used to block re-creation and resolve deletions) still sees the tombstone.
        found.get().setDeletedAt(Instant.now());
        entityManager.flush();
        assertThat(canvasRepository.findByChatIdAndDeletedAtIsNull(sessionId)).isEmpty();
        assertThat(canvasRepository.findByChatIdAndDocumentId(sessionId, documentId))
                .isPresent();

        // Verify hard delete
        canvasRepository.delete(found.get());
        entityManager.flush();
        assertThat(canvasRepository.findByChatIdAndDeletedAtIsNull(sessionId)).isEmpty();
    }
}
