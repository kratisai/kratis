package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.ScratchpadEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScratchpadRepository extends JpaRepository<ScratchpadEntity, Long> {

    List<ScratchpadEntity> findByChatId(UUID chatId);

    void deleteByChatId(UUID chatId);

    void deleteByChatIdAndFact(UUID chatId, String fact);
}
