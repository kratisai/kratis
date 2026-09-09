package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ChatEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRepository extends JpaRepository<ChatEntity, UUID> {

    List<ChatEntity> findByTeamId(UUID teamId);

    /** Get last 20 chats for a specific team, ordered by most recently updated. */
    List<ChatEntity> findTop20ByTeamIdOrderByUpdatedAtDesc(UUID teamId);

    /** Get last 20 chats for a specific team created by a specific user. */
    List<ChatEntity> findTop20ByTeamIdAndUserIdOrderByUpdatedAtDesc(UUID teamId, UUID userId);

    /** Get last 20 active (non-archived) chats for a specific team, ordered by most recently updated. */
    List<ChatEntity> findTop20ByTeamIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID teamId);

    /** Get last 20 active (non-archived) chats for a specific team created by a specific user. */
    List<ChatEntity> findTop20ByTeamIdAndUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID teamId, UUID userId);

    /** Get last 20 archived chats for a specific team, ordered by most recently updated. */
    List<ChatEntity> findTop20ByTeamIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(UUID teamId);

    /** Get last 20 archived chats for a specific team created by a specific user. */
    List<ChatEntity> findTop20ByTeamIdAndUserIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(UUID teamId, UUID userId);

    /** Check if a chat exists by ID and team (for authorization). */
    Optional<ChatEntity> findByIdAndTeamId(UUID chatId, UUID teamId);

    /**
     * Update a chat title without bumping {@code updated_at}, so re-titling does not change the
     * chat's ordering in the history list. Returns the number of rows updated.
     */
    @Modifying
    @Query("UPDATE ChatEntity c SET c.title = :title WHERE c.id = :chatId")
    int updateTitle(@Param("chatId") UUID chatId, @Param("title") String title);

    @Modifying
    @Query("UPDATE ChatEntity c SET c.archivedAt = :archivedAt WHERE c.id = :chatId")
    int updateArchivedAt(@Param("chatId") UUID chatId, @Param("archivedAt") Instant archivedAt);
}
