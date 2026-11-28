package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmallDtoCoverageTest {

    @Test
    void chatDto() {
        UUID chatId = UUID.randomUUID();
        Instant now = Instant.now();
        ChatDto dto = new ChatDto(
                chatId, "Chat Title", false, TopicStatus.WORKING, now, LastActivityType.CHAT, chatId.toString());
        assertThat(dto.id()).isEqualTo(chatId);
        assertThat(dto.title()).isEqualTo("Chat Title");
        assertThat(dto.allowAllPermissions()).isFalse();
        assertThat(dto.status()).isEqualTo(TopicStatus.WORKING);
        assertThat(dto.status().getValue()).isEqualTo("working");
        assertThat(dto.lastActivityAt()).isEqualTo(now);
        assertThat(dto.lastActivityType()).isEqualTo(LastActivityType.CHAT);
        assertThat(dto.lastActivityTargetId()).isEqualTo(chatId.toString());
    }

    @Test
    void topicStatusWireValues() {
        assertThat(Arrays.stream(TopicStatus.values()).map(TopicStatus::getValue))
                .containsExactly(
                        "working", "Planning", "Idle", "MR pushed", "MR merged", "Ready to publish", "Blocked");
        assertThat(TopicStatus.PLANNING.getValue()).isEqualTo("Planning");
    }
}
