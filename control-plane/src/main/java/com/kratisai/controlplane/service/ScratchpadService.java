package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.ScratchpadEntity;
import com.kratisai.controlplane.repository.ScratchpadRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service for managing scratchpad facts per chat. */
@Service
public class ScratchpadService {

    private final ScratchpadRepository scratchpadRepository;

    public ScratchpadService(ScratchpadRepository scratchpadRepository) {
        this.scratchpadRepository = scratchpadRepository;
    }

    @Transactional
    public ScratchpadEntity addFact(UUID chatId, String fact) {
        return scratchpadRepository.save(new ScratchpadEntity(chatId, fact));
    }

    @Transactional
    public void deleteFact(UUID chatId, String fact) {
        scratchpadRepository.deleteByChatIdAndFact(chatId, fact);
    }

    @Transactional
    public void deleteAllFacts(UUID chatId) {
        scratchpadRepository.deleteByChatId(chatId);
    }

    public List<ScratchpadEntity> getFacts(UUID chatId) {
        return scratchpadRepository.findByChatId(chatId);
    }

    public String getFactsAsString(UUID chatId) {
        List<ScratchpadEntity> facts = getFacts(chatId);
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        for (ScratchpadEntity fact : facts) {
            sb.append("- ").append(fact.getFact()).append("\n");
        }
        return sb.toString();
    }
}
