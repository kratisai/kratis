package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.event.ChatTurnCompletedEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatTurnEventService {

    private final ApplicationEventPublisher eventPublisher;

    public ChatTurnEventService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // SUPPORTS keeps the declarative boundary the publish rule expects while avoiding a
    // connection acquisition and commit for this in-memory, synchronously consumed event.
    @Transactional(propagation = Propagation.SUPPORTS)
    public void publishTurnCompleted(UUID teamId, UUID chatId) {
        eventPublisher.publishEvent(new ChatTurnCompletedEvent(teamId, chatId));
    }
}
