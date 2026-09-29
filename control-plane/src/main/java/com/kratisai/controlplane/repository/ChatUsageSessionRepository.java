package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ChatUsageSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatUsageSessionRepository extends JpaRepository<ChatUsageSession, UUID> {

    List<ChatUsageSession> findByChatId(UUID chatId);

    @Query("SELECT cus FROM ChatUsageSession cus WHERE cus.usage.virtualKey = :virtualKey")
    Optional<ChatUsageSession> findByVirtualKey(@Param("virtualKey") String virtualKey);

    @Query("SELECT cus FROM ChatUsageSession cus WHERE cus.chat.id = :chatId ORDER BY cus.startedAt DESC")
    List<ChatUsageSession> findByChatIdOrderByStartedAtDesc(@Param("chatId") UUID chatId);

    @Query("SELECT COALESCE(SUM(cus.usage.totalSpend), 0.0) FROM ChatUsageSession cus")
    double sumTotalSpend();
}
