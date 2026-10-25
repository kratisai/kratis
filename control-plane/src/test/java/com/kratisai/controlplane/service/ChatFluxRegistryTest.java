package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ChatFluxRegistryTest {

    private ChatFluxRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChatFluxRegistry();
    }

    @Test
    void registerAndGet_returnsRegisteredFlux() {
        UUID chatId = UUID.randomUUID();
        Flux<ClientPayload.ChatStreamPayload> flux = Flux.empty();

        registry.register(chatId, flux);

        assertThat(registry.containsKey(chatId)).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(chatId)).isSameAs(flux);
    }

    @Test
    void subscriberCancellation_doesNotRemoveRegisteredFlux() {
        UUID chatId = UUID.randomUUID();
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        registry.register(chatId, flux);
        flux.subscribe().dispose();

        assertThat(registry.containsKey(chatId)).isTrue();
        assertThat(registry.get(chatId)).isSameAs(flux);
    }

    @Test
    void remove_removesRegisteredFlux() {
        UUID chatId = UUID.randomUUID();
        Flux<ClientPayload.ChatStreamPayload> flux = Flux.empty();

        registry.register(chatId, flux);
        registry.remove(chatId, flux);

        assertThat(registry.containsKey(chatId)).isFalse();
        assertThat(registry.get(chatId)).isNull();
    }

    @Test
    void remove_doesNotRemoveNewerStreamForSameChat() {
        UUID chatId = UUID.randomUUID();
        Sinks.Many<ClientPayload.ChatStreamPayload> firstSink =
                Sinks.many().replay().all();
        Sinks.Many<ClientPayload.ChatStreamPayload> secondSink =
                Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> first = firstSink.asFlux();
        Flux<ClientPayload.ChatStreamPayload> second = secondSink.asFlux();

        registry.register(chatId, first);
        registry.register(chatId, second);
        registry.remove(chatId, first);

        assertThat(registry.get(chatId)).isSameAs(second);
    }
}
