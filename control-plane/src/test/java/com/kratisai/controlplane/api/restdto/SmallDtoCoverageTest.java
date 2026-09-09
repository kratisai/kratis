package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmallDtoCoverageTest {

    @Test
    void chatDto() {
        UUID chatId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Instant now = Instant.now();
        ChatDto dto = new ChatDto(chatId, teamId, "Chat Title", "Alice", now, now, now, 0.0, 0L, 0L, 0L, now);
        assertThat(dto.id()).isEqualTo(chatId);
        assertThat(dto.teamId()).isEqualTo(teamId);
        assertThat(dto.title()).isEqualTo("Chat Title");
        assertThat(dto.createdByDisplayName()).isEqualTo("Alice");
        assertThat(dto.createdAt()).isEqualTo(now);
        assertThat(dto.updatedAt()).isEqualTo(now);
        assertThat(dto.archivedAt()).isEqualTo(now);
        assertThat(dto.totalSpend()).isEqualTo(0.0);
    }
}
