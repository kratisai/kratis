package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class ChatFluxRegistryTest {

    private ChatFluxRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChatFluxRegistry();
    }

    @Test
    void registerAndGet_returnsRegisteredFlux() {
        UUID chatId = UUID.randomUUID();
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        registry.register(chatId, flux);

        assertThat(registry.containsKey(chatId)).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(chatId)).isNotNull();
    }

    @Test
    void autoRemovesOnFluxComplete() {
        UUID chatId = UUID.randomUUID();
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        registry.register(chatId, flux);
        Flux<ClientPayload.ChatStreamPayload> registeredFlux = registry.get(chatId);

        sink.tryEmitNext(new ClientPayload.CompleteResult("msg-1"));
        sink.tryEmitComplete();

        StepVerifier.create(registeredFlux)
                .expectNextMatches(r -> r instanceof ClientPayload.CompleteResult)
                .verifyComplete();

        assertThat(registry.containsKey(chatId)).isFalse();
        assertThat(registry.get(chatId)).isNull();
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void autoRemovesOnFluxError() {
        UUID chatId = UUID.randomUUID();
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        registry.register(chatId, flux);
        Flux<ClientPayload.ChatStreamPayload> registeredFlux = registry.get(chatId);

        sink.tryEmitError(new RuntimeException("Test failure"));

        StepVerifier.create(registeredFlux).expectError(RuntimeException.class).verify();

        assertThat(registry.containsKey(chatId)).isFalse();
        assertThat(registry.get(chatId)).isNull();
    }

    @Test
    void getAndRemove_removesAndReturnsFlux() {
        UUID chatId = UUID.randomUUID();
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        registry.register(chatId, flux);

        Flux<ClientPayload.ChatStreamPayload> removed = registry.getAndRemove(chatId);
        assertThat(removed).isNotNull();
        assertThat(registry.containsKey(chatId)).isFalse();
        assertThat(registry.get(chatId)).isNull();
    }
}
