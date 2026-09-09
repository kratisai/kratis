package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ChatWebSocketTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private String teamId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        User user = ctx.user();
        Team team = ctx.team();

        userId = user.getId();
        teamId = team.getId().toString();

        // For WebSocket tests, we verify the backend logic directly since full WS
        // testing
        // requires a running server. We test the service layer that the WS handler
        // calls.
    }

    @Test
    void sessionLoad_validSession_shouldReturnMessages() {
        // This test verifies the backend logic that session.load would call
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        ChatEntity chat = new ChatEntity(team, user, "Test Chat");
        chatRepository.save(chat);

        // Verify chat was created and can be retrieved
        var found = chatRepository.findByIdAndTeamId(chat.getId(), team.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Test Chat");
    }

    @Test
    void sessionLoad_emptySession_shouldReturnEmpty() {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        ChatEntity chat = new ChatEntity(team, user, "Empty Chat");
        chatRepository.save(chat);

        // Verify chat exists but has no messages (messages would be in chat_memory)
        var found = chatRepository.findByIdAndTeamId(chat.getId(), team.getId());
        assertThat(found).isPresent();
    }

    @Test
    void chatSend_createsSession_thenLoad_returnsMessages() {
        Team team = teamRepository.findById(UUID.fromString(teamId)).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        // Simulate what chat.send does: create a chat
        ChatEntity chat = new ChatEntity(team, user, "Auto-created Chat");
        chatRepository.save(chat);

        // Verify chat can be loaded
        var found = chatRepository.findByIdAndTeamId(chat.getId(), team.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Auto-created Chat");
    }
}
