package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class JsonRpcInboundRetryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void invoke_successfulStream_emitsWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = JsonRpcInbound.invoke(() -> {
            attempts.incrementAndGet();
            return Flux.just("hello", "world");
        });

        StepVerifier.create(stream).expectNext("hello", "world").verifyComplete();

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void invoke_transientFailureInFlux_retriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = JsonRpcInbound.invoke(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new TransientDataAccessResourceException("Connection lost"));
            }
            return Flux.just("recovered");
        });

        StepVerifier.create(stream).expectNext("recovered").verifyComplete();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void invoke_syncTransientException_retriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = JsonRpcInbound.invoke(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("Failed to open connection");
            }
            return Flux.just("success");
        });

        StepVerifier.create(stream).expectNext("success").verifyComplete();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void invoke_nonTransientError_failsImmediatelyWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = JsonRpcInbound.invoke(() -> {
            attempts.incrementAndGet();
            return Flux.error(new IllegalArgumentException("Invalid payload"));
        });

        StepVerifier.create(stream).expectError(IllegalArgumentException.class).verify();

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void invoke_exhaustsMaxRetries_emitsFailure() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = JsonRpcInbound.invoke(() -> {
            attempts.incrementAndGet();
            return Flux.error(new TransientDataAccessResourceException("Persistent DB outage"));
        });

        StepVerifier.create(stream)
                .expectError(TransientDataAccessResourceException.class)
                .verify();

        assertThat(attempts.get()).isEqualTo(3); // 1 initial + 2 retries
    }

    @Test
    void deserializeParams_handlesNoParamsPayload() throws Exception {
        Object result = JsonRpcInbound.deserializeParams(objectMapper, null, ClientRpcPayload.Ping.class);
        assertThat(result).isInstanceOf(ClientRpcPayload.Ping.class);
    }

    @Test
    void invoke_emptyResult_emitsEmpty() {
        Flux<ClientPayload> stream = JsonRpcInbound.invoke(() -> null);
        StepVerifier.create(stream).verifyComplete();
    }
}
