package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.service.ChatService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;

/** Component test covering the full ChatTitleService flow against the fake LLM and real database. */
@SpringIntegrationTest
class ChatTitleServiceComponentTest {

    @Autowired
    private ChatTitleService chatTitleService;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Team team;
    private User user;
    private ChatEntity chat;
    private ModelProvider provider;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        fakeChatModel.reset();

        var context = testDataFactory.createUserAndTeam();
        team = context.team();
        user = context.user();
        chat = chatService.createChat(team.getId(), user.getId(), "Initial fallback title");
        provider = testDataFactory.createModelProviderWithLiteLLM(
                team, "OpenAI", ProviderType.OPENAI, "sk-dummy", List.of("gpt-4o"));
    }

    private void seedMessages() {
        chatService.saveUserMessage(chat.getId(), "Debug the flaky test suite");
        chatMemory.add(chat.getId().toString(), new AssistantMessage("The flaky tests are fixed."));
    }

    @Test
    void generatesAndPersistsValidTitle() {
        seedMessages();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Debug and fix the flaky test suite now")
                .build());

        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), chat.getId());

        assertThat(titleOf(chat.getId())).isEqualTo("Debug and fix the flaky test suite now");
    }

    @Test
    void generatesInitialTitleFromPromptWithoutConversationHistory() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Debug and fix the flaky test suite now")
                .build());

        String title = chatTitleService.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo("Debug and fix the flaky test suite now");
        Prompt prompt = fakeChatModel.getInvocations().getFirst().prompt();
        assertThat(prompt.getContents()).contains("Debug the flaky test suite");
        assertThat(prompt.getContents()).doesNotContain("The flaky tests are fixed.");
    }

    @Test
    void generateInitialTitle_fallsBackToPromptWhenLlmCallFails() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .throwsException(new RuntimeException("Simulated LLM failure"))
                .build());

        String title = chatTitleService.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo("Debug the flaky test suite");
    }

    @Test
    void generateInitialTitle_trimsSummaryBeyondTwiceMaxWords() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response(String.join(" ", Collections.nCopies(61, "word")))
                .build());

        String title = chatTitleService.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo(String.join(" ", Collections.nCopies(60, "word")));
    }

    @Test
    void promptEnforcesSte100AndWordConstraints() {
        seedMessages();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Debug and fix the flaky test suite now")
                .build());

        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), chat.getId());

        Prompt prompt = fakeChatModel.getInvocations().getFirst().prompt();
        String contents = prompt.getContents();
        assertThat(contents).contains("ASD-STE100 Simplified Technical English");
        assertThat(contents).contains("between 8 and 30 words");
        assertThat(contents).contains("Output only the title");
        assertThat(contents).contains("Debug the flaky test suite");
        assertThat(contents).contains("The flaky tests are fixed.");
    }

    @Test
    void keepsExistingTitleWhenGeneratedTitleHasTooFewWords() {
        seedMessages();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Hi")
                .build());

        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), chat.getId());

        assertThat(titleOf(chat.getId())).isEqualTo("Initial fallback title");
    }

    @Test
    void skipsUnpersistedChatWithoutCallingLlm() {
        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), UUID.randomUUID());

        assertThat(fakeChatModel.getInvocations()).isEmpty();
    }

    @Test
    void evolvesTitleAsConversationScopeChanges() {
        seedMessages();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Debug and fix the flaky test suite now")
                .build());

        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), chat.getId());
        assertThat(titleOf(chat.getId())).isEqualTo("Debug and fix the flaky test suite now");

        // The conversation scope moves on to a new topic.
        chatService.saveUserMessage(chat.getId(), "Now prepare the release for production");
        chatMemory.add(chat.getId().toString(), new AssistantMessage("Release plan is ready."));
        fakeChatModel.clearMatchers();
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Prepare the final release for production deployment now")
                .build());

        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), chat.getId());

        assertThat(titleOf(chat.getId())).isEqualTo("Prepare the final release for production deployment now");
    }

    @Test
    void multipleConversationsDoNotShareTitles() {
        seedMessages();
        ChatEntity other = chatService.createChat(team.getId(), user.getId(), "Another fallback");
        chatService.saveUserMessage(other.getId(), "Set up the observability stack");
        chatMemory.add(other.getId().toString(), new AssistantMessage("Observability is configured."));

        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Set up the observability stack")
                .response("Set up monitoring for the new production stack")
                .build());
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Output only the title")
                .response("Debug and fix the flaky test suite now")
                .build());

        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), chat.getId());
        chatTitleService.maybeUpdateTitle(provider, "gpt-4o", team.getId(), other.getId());

        assertThat(titleOf(chat.getId())).isEqualTo("Debug and fix the flaky test suite now");
        assertThat(titleOf(other.getId())).isEqualTo("Set up monitoring for the new production stack");
    }

    private String titleOf(UUID chatId) {
        return chatRepository.findById(chatId).orElseThrow().getTitle();
    }
}
