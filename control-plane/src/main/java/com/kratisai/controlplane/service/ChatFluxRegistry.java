package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Registry holding active agent response Fluxes by chatId so late-joining
 * WebSocket subscribers can attach to in-progress streams.
 */
@Component
public class ChatFluxRegistry {

    private final Map<UUID, Flux<ClientPayload.ChatStreamPayload>> activeStreams = new ConcurrentHashMap<>();

    public void register(UUID chatId, Flux<ClientPayload.ChatStreamPayload> flux) {
        Flux<ClientPayload.ChatStreamPayload> managedFlux =
                flux.doFinally(signalType -> activeStreams.remove(chatId, activeStreams.get(chatId)));
        activeStreams.put(chatId, managedFlux);
    }

    public Flux<ClientPayload.ChatStreamPayload> get(UUID chatId) {
        return activeStreams.get(chatId);
    }

    public Flux<ClientPayload.ChatStreamPayload> getAndRemove(UUID chatId) {
        return activeStreams.remove(chatId);
    }

    public boolean containsKey(UUID chatId) {
        return activeStreams.containsKey(chatId);
    }

    public int size() {
        return activeStreams.size();
    }
}
