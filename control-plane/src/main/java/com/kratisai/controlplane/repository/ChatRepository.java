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

    String SUMMARY_SELECT = """
            SELECT
                c.id                    AS id,
                c.title                 AS title,
                c.allow_all_permissions AS allowAllPermissions,
                c.updated_at            AS updatedAt,
                mem.last_message_at     AS lastMessageAt,
                le.id                   AS latestExecutionId,
                act.last_activity_at    AS latestExecutionActivityAt,
                COALESCE(act.pending_hitl, FALSE) AS pendingHitl
            FROM chats c
            LEFT JOIN LATERAL (
                SELECT MAX(m.created_at) AS last_message_at
                FROM chat_memory m
                WHERE m.chat_id = c.id
            ) mem ON TRUE
            LEFT JOIN LATERAL (
                SELECT e.id, e.started_at
                FROM sandbox_executions e
                WHERE e.chat_id = c.id
                ORDER BY e.started_at DESC
                LIMIT 1
            ) le ON TRUE
            LEFT JOIN LATERAL (
                SELECT
                    MAX(a.created_at) FILTER (WHERE a.activity_type <> 'ERROR') AS last_activity_at,
                    BOOL_OR(a.status = 'PENDING' AND a.detail -> 'hitl' ->> 'state' = 'awaiting_human')
                        AS pending_hitl
                FROM sandbox_execution_activities a
                WHERE a.execution_id = le.id
            ) act ON TRUE
            """;

    List<ChatEntity> findByTeamId(UUID teamId);

    interface RecentChatRow {
        UUID getId();

        String getTitle();

        boolean isAllowAllPermissions();

        Instant getUpdatedAt();

        Instant getLastMessageAt();

        UUID getLatestExecutionId();

        Instant getLatestExecutionActivityAt();

        boolean isPendingHitl();
    }

    /**
     * Most recently active chats, where activity is the latest message, canvas edit, execution
     * start, non-error execution activity, publish, or diff snapshot. Chats with no activity fall
     * back to their last update.
     */
    @Query(value = SUMMARY_SELECT + """
                    WHERE c.team_id = :teamId
                      AND (CAST(:userId AS uuid) IS NULL OR c.user_id = CAST(:userId AS uuid))
                      AND (CAST(:archived AS boolean) IS NULL
                           OR (c.archived_at IS NOT NULL) = CAST(:archived AS boolean))
                    ORDER BY COALESCE(
                        GREATEST(
                            mem.last_message_at,
                            (SELECT MAX(cv.updated_at) FROM canvas cv
                             WHERE cv.chat_id = c.id AND cv.deleted_at IS NULL),
                            le.started_at,
                            act.last_activity_at,
                            (SELECT MAX(cs.created_at) FROM change_sets cs WHERE cs.execution_id = le.id),
                            (SELECT ds.updated_at FROM execution_diff_snapshots ds
                             WHERE ds.execution_id = le.id)
                        ),
                        c.updated_at
                    ) DESC
                    LIMIT :limit
                    """, nativeQuery = true)
    List<RecentChatRow> findRecentByLastActivity(
            @Param("teamId") UUID teamId,
            @Param("userId") UUID userId,
            @Param("archived") Boolean archived,
            @Param("limit") int limit);

    @Query(value = SUMMARY_SELECT + "WHERE c.id = :chatId AND c.team_id = :teamId", nativeQuery = true)
    Optional<RecentChatRow> findSummaryByIdAndTeamId(@Param("chatId") UUID chatId, @Param("teamId") UUID teamId);

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

    @Modifying
    @Query("UPDATE ChatEntity c SET c.updatedAt = :updatedAt WHERE c.id = :chatId")
    int touchUpdatedAt(@Param("chatId") UUID chatId, @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query("UPDATE ChatEntity c SET c.allowAllPermissions = :enabled WHERE c.id = :chatId")
    int updateAllowAllPermissions(@Param("chatId") UUID chatId, @Param("enabled") boolean enabled);
}
