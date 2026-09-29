package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Lob;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ChatMemoryMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void testChatMemoryEntityMapping() {
        // Create and save a chat memory entity
        UUID chatId = UUID.randomUUID();
        ChatMemoryEntity entity = new ChatMemoryEntity(chatId, 0, MessageRole.USER, "Hello, world!", null, null, null);
        entityManager.persist(entity);
        entityManager.flush();

        // Verify save
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getCreatedAt()).isNotNull();

        // Verify find
        ChatMemoryEntity found = entityManager.find(ChatMemoryEntity.class, entity.getId());
        assertThat(found.getMessageText()).isEqualTo("Hello, world!");
        assertThat(found.getMessageType()).isEqualTo(MessageRole.USER);
        assertThat(found.getMessageIndex()).isEqualTo(0);
        assertThat(found.getChatId()).isEqualTo(chatId);

        // Verify delete
        entityManager.remove(found);
        entityManager.flush();
        assertThat(entityManager.find(ChatMemoryEntity.class, entity.getId())).isNull();
    }

    @Test
    void testChatMemoryEntityDoesNotUseLobOnTextColumns() {
        // Regression test: @Lob on TEXT columns causes PostgreSQL to use Large Objects,
        // which fails with "Large Objects may not be used in auto-commit mode" when
        // accessed outside a proper transaction context (e.g., from virtual threads).
        // TEXT columns should NOT have @Lob - PostgreSQL handles them natively.
        try {
            Field messageTextField = ChatMemoryEntity.class.getDeclaredField("messageText");
            assertThat(messageTextField.getAnnotation(Lob.class))
                    .as("messageText field should NOT have @Lob annotation (causes PostgreSQL LOB issues)")
                    .isNull();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("ChatMemoryEntity should have messageText field", e);
        }
    }
}
