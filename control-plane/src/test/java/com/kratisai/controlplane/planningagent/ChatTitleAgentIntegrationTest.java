package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the full wiring: PlanningAgentService runs the ReAct loop, then ChatTitleService makes a
 * quick one-shot title call that updates the persisted chat title (and evolves it on later turns).
 */
@SpringIntegrationTest
class ChatTitleAgentIntegrationTest extends AgentTestHarness {

    @Autowired
    private PlanningAgentService planningAgentService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private FakeChatModel fakeChatModel;

    private UUID teamId;
    private UUID providerId;
    private ChatEntity chat;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        setupTeamAndLogin();

        User user = userRepo.findAll().getFirst();
        Team team = createTeamWithProvider(user.getId());
        teamId = team.getId();
        providerId =
                modelProviderRepo.findFirstByTeamIdAndIsActiveTrue(teamId).get().getId();
        chat = chatRepository.save(new ChatEntity(team, user, "Initial fallback title"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void firstTurnGeneratesTitleAfterAgentCompletes() {
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Fix the broken build pipeline in CI now")
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("The build is fixed.").build());

        planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", chat.getId(), "Fix the broken build")
                .collectList()
                .block();

        awaitTitle(chat.getId(), "Fix the broken build pipeline in CI now");

        // The title request is a single non-thinking call that includes conversation context.
        Prompt titlePrompt = fakeChatModel.getInvocations().getLast().prompt();
        assertThat(titlePrompt.getContents()).contains("Output only the title");
    }

    @Test
    void followUpTurnEvolvesTitleWithNewScope() {
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Fix the broken build pipeline in CI now")
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("The build is fixed.").build());

        planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", chat.getId(), "Fix the broken build")
                .collectList()
                .block();
        awaitTitle(chat.getId(), "Fix the broken build pipeline in CI now");

        fakeChatModel.clearMatchers();
        fakeChatModel.addMatcherFirst(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Prepare the final release for production deployment now")
                .build());
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("Release deployed.").build());

        planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", chat.getId(), "Deploy the release now")
                .collectList()
                .block();

        awaitTitle(chat.getId(), "Prepare the final release for production deployment now");
    }

    @Test
    void unpersistedSessionDoesNotTriggerTitleCall() {
        fakeChatModel.addMatcher(
                PromptMatcher.builder().response("The build is fixed.").build());

        planningAgentService
                .streamMessage(teamId, providerId, "gpt-4o", UUID.randomUUID(), "Fix the broken build")
                .collectList()
                .block();

        assertThat(fakeChatModel.getInvocations()).hasSize(1);
        assertThat(fakeChatModel.getInvocations().getFirst().prompt().getContents())
                .doesNotContain("Output only the title");
    }

    private void awaitTitle(UUID chatId, String expectedTitle) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Optional<ChatEntity> found = chatRepository.findById(chatId);
            if (found.isPresent() && expectedTitle.equals(found.get().getTitle())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while awaiting chat title");
            }
        }
        fail("Chat %s title was not updated to '%s'".formatted(chatId, expectedTitle));
    }
}
