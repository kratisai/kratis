package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class PlanningAgentServiceTest {

    @Test
    void replaySink_replaysAllItemsToLateSubscribers() {
        Sinks.Many<ClientPayload.ChatStreamPayload> sink = Sinks.many().replay().all();
        Flux<ClientPayload.ChatStreamPayload> flux = sink.asFlux();

        UUID chatId = UUID.randomUUID();

        // Emit item 1 and item 2 before any subscriber
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "m1", "Hello "));
        sink.tryEmitNext(new ClientPayload.MessageChunkResult(chatId, "m1", "world!"));

        // First late subscriber
        List<ClientPayload.ChatStreamPayload> list1 = new ArrayList<>();
        flux.subscribe(list1::add);

        // Emit item 3
        sink.tryEmitNext(new ClientPayload.CompleteResult("m1"));
        sink.tryEmitComplete();

        // Second late subscriber (subscribes after completion)
        List<ClientPayload.ChatStreamPayload> list2 = new ArrayList<>();
        flux.subscribe(list2::add);

        assertThat(list1).hasSize(3);
        assertThat(list2).hasSize(3);

        assertThat(((ClientPayload.MessageChunkResult) list1.get(0)).content()).isEqualTo("Hello ");
        assertThat(((ClientPayload.MessageChunkResult) list1.get(1)).content()).isEqualTo("world!");
        assertThat(list1.get(2)).isInstanceOf(ClientPayload.CompleteResult.class);

        assertThat(((ClientPayload.MessageChunkResult) list2.get(0)).content()).isEqualTo("Hello ");
        assertThat(((ClientPayload.MessageChunkResult) list2.get(1)).content()).isEqualTo("world!");
        assertThat(list2.get(2)).isInstanceOf(ClientPayload.CompleteResult.class);
    }
}
