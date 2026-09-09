package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ChatMemoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentChatMemoryRepository extends JpaRepository<ChatMemoryEntity, Long> {

    List<ChatMemoryEntity> findByChatIdOrderByCreatedAtAsc(UUID chatId);

    void deleteByChatId(UUID chatId);
}
