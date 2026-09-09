package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.PromptMatcher;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.ChatService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ChatTitleServiceTest {

    private static final int MIN_WORDS = 8;
    private static final int MAX_WORDS = 30;

    private static final String VALID_TITLE = "Debug and fix the flaky test suite now";

    private FakeChatModel fakeChatModel;
    private ChatModelFactory chatModelFactory;
    private ChatMemory chatMemory;
    private ChatService chatService;
    private ChatTitleService service;
    private ModelProvider provider;
    private UUID chatId;
    private UUID teamId;

    @BeforeEach
    void setUp() {
        fakeChatModel = new FakeChatModel();
        chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.createChatModel(any(), any(), anyBoolean())).thenReturn(fakeChatModel);
        chatMemory = mock(ChatMemory.class);
        chatService = mock(ChatService.class);
        service = new ChatTitleService(chatModelFactory, chatMemory, chatService, MIN_WORDS, MAX_WORDS, 10);
        provider = new ModelProvider("Test Provider", ProviderType.OPENAI, "test-api-key", null);
        chatId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        when(chatService.findTitle(chatId)).thenReturn(Optional.of("Initial long user message"));
        when(chatService.updateTitle(eq(chatId), eq(teamId), any())).thenReturn(true);
        when(chatMemory.get(chatId.toString()))
                .thenReturn(List.of(new UserMessage("Debug the build pipeline"), new AssistantMessage("Done.")));
    }

    @Test
    void sanitizeTitle_removesSurroundingQuotesAndCollapsesWhitespace() {
        assertThat(ChatTitleService.sanitizeTitle("\"  Debug and fix the flaky test suite now  \""))
                .isEqualTo(VALID_TITLE);
        assertThat(ChatTitleService.sanitizeTitle("'Debug and fix the flaky test suite now'"))
                .isEqualTo(VALID_TITLE);
        assertThat(ChatTitleService.sanitizeTitle("  Debug  and fix the flaky  test suite now\nand more lines  "))
                .isEqualTo(VALID_TITLE);
    }

    @Test
    void sanitizeTitle_blankOrNull_returnsEmpty() {
        assertThat(ChatTitleService.sanitizeTitle(null)).isEmpty();
        assertThat(ChatTitleService.sanitizeTitle("   ")).isEmpty();
    }

    @Test
    void wordCount_countsWhitespaceSeparatedWords() {
        assertThat(ChatTitleService.wordCount("Debug and fix the flaky test suite now"))
                .isEqualTo(8);
        assertThat(ChatTitleService.wordCount("  Debug   fix  now  ")).isEqualTo(3);
        assertThat(ChatTitleService.wordCount("  ")).isZero();
        assertThat(ChatTitleService.wordCount(null)).isZero();
    }

    @Test
    void isWithinLimits_respectsConfiguredWordBoundaries() {
        service = new ChatTitleService(chatModelFactory, chatMemory, chatService, 8, 30, 10);
        assertThat(service.isWithinLimits("one two three four five six seven eight"))
                .isTrue();
        assertThat(service.isWithinLimits(String.join(" ", Collections.nCopies(60, "word"))))
                .isTrue();
        assertThat(service.isWithinLimits("one two three four five six seven")).isFalse();
        assertThat(service.isWithinLimits(String.join(" ", Collections.nCopies(61, "word"))))
                .isFalse();
        assertThat(service.isWithinLimits(null)).isFalse();
        assertThat(service.isWithinLimits("")).isFalse();
    }

    @Test
    void isWithinLimits_rejectsTitleExceedingDatabaseColumnLength() {
        service = new ChatTitleService(chatModelFactory, chatMemory, chatService, 8, 30, 10);
        String tooLong = String.join(" ", Collections.nCopies(9, "w".repeat(60)));
        assertThat(ChatTitleService.wordCount(tooLong)).isEqualTo(9);
        assertThat(service.isWithinLimits(tooLong)).isFalse();
    }

    @Test
    void maybeUpdateTitle_usesNonThinkingModelAndPersistsTitle() {
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        ArgumentCaptor<ModelProvider> providerCaptor = ArgumentCaptor.forClass(ModelProvider.class);
        ArgumentCaptor<Boolean> thinkingCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(chatModelFactory).createChatModel(providerCaptor.capture(), eq("gpt-4o"), thinkingCaptor.capture());
        assertThat(providerCaptor.getValue()).isSameAs(provider);
        assertThat(thinkingCaptor.getValue()).isFalse();
        verify(chatService).updateTitle(chatId, teamId, VALID_TITLE);
    }

    @Test
    void maybeUpdateTitle_ignoresTitleWithTooFewWords() {
        fakeChatModel.addMatcher(PromptMatcher.builder().response("Hi").build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        verify(chatService, never()).updateTitle(eq(chatId), eq(teamId), any());
    }

    @Test
    void maybeUpdateTitle_ignoresTitleWithTooManyWords() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .response(String.join(" ", Collections.nCopies(61, "word")))
                .build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        verify(chatService, never()).updateTitle(eq(chatId), eq(teamId), any());
    }

    @Test
    void maybeUpdateTitle_skipsUnpersistedChatWithoutCallingLlm() {
        UUID unknownChat = UUID.randomUUID();
        when(chatService.findTitle(unknownChat)).thenReturn(Optional.empty());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, unknownChat);

        assertThat(fakeChatModel.getInvocations()).isEmpty();
        verify(chatService, never()).updateTitle(any(), any(), any());
    }

    @Test
    void maybeUpdateTitle_skipsChatWithoutMessages() {
        when(chatMemory.get(chatId.toString())).thenReturn(List.of());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        assertThat(fakeChatModel.getInvocations()).isEmpty();
        verify(chatService, never()).updateTitle(any(), any(), any());
    }

    @Test
    void maybeUpdateTitle_keepsCurrentTitleWhenGeneratedTitleIsUnchanged() {
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());
        when(chatService.findTitle(chatId)).thenReturn(Optional.of(VALID_TITLE));

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        verify(chatService, never()).updateTitle(eq(chatId), eq(teamId), any());
    }

    @Test
    void maybeUpdateTitle_doesNotRetryWhenUpdateFails() {
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());
        when(chatService.updateTitle(eq(chatId), eq(teamId), any())).thenReturn(false);

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        verify(chatService).updateTitle(chatId, teamId, VALID_TITLE);
    }

    @Test
    void maybeUpdateTitle_withNullArguments_skips() {
        service.maybeUpdateTitle(null, "gpt-4o", teamId, chatId);
        service.maybeUpdateTitle(provider, null, teamId, chatId);
        service.maybeUpdateTitle(provider, "gpt-4o", null, chatId);
        service.maybeUpdateTitle(provider, "gpt-4o", teamId, null);

        assertThat(fakeChatModel.getInvocations()).isEmpty();
        verify(chatService, never()).updateTitle(any(), any(), any());
    }

    @Test
    void maybeUpdateTitle_withOnlyNonConversationalMessages_stillCallsLlm() {
        when(chatMemory.get(chatId.toString())).thenReturn(List.of(new SystemMessage("system only")));
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        verify(chatService).updateTitle(chatId, teamId, VALID_TITLE);
    }

    @Test
    void maybeUpdateTitle_swallowsLlmFailures() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .throwsException(new RuntimeException("Simulated LLM failure"))
                .build());

        assertThatCode(() -> service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId))
                .doesNotThrowAnyException();

        verify(chatService, never()).updateTitle(eq(chatId), eq(teamId), any());
    }

    @Test
    void maybeUpdateTitle_sendsOnlyRecentConversationalMessages() {
        when(chatMemory.get(chatId.toString()))
                .thenReturn(List.of(
                        new UserMessage("msg-0"),
                        new AssistantMessage("msg-1"),
                        new UserMessage("msg-2"),
                        new AssistantMessage("msg-3"),
                        new UserMessage("msg-4"),
                        new AssistantMessage("msg-5"),
                        new UserMessage("msg-6"),
                        new AssistantMessage("msg-7"),
                        new UserMessage("msg-8"),
                        new AssistantMessage("msg-9"),
                        new UserMessage("msg-10"),
                        new AssistantMessage("msg-11")));
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        Prompt prompt = fakeChatModel.getInvocations().getFirst().prompt();
        List<Message> instructions = prompt.getInstructions();
        List<Message> conversational = instructions.stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .toList();
        assertThat(conversational).hasSize(11);
        assertThat(conversational.getFirst().getText()).isEqualTo("msg-2");
        assertThat(conversational.get(9).getText()).isEqualTo("msg-11");
        assertThat(conversational.getLast()).isInstanceOf(UserMessage.class);
    }

    @Test
    void maybeUpdateTitle_endsWithUserTurnForGeminiRoleConstraint() {
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        Prompt prompt = fakeChatModel.getInvocations().getFirst().prompt();
        List<Message> conversational = prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .toList();
        assertThat(conversational).hasSize(3);
        assertThat(conversational.get(0)).isInstanceOf(UserMessage.class);
        assertThat(conversational.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(conversational.get(2)).isInstanceOf(UserMessage.class).satisfies(m -> assertThat(m.getText())
                .isEqualTo("Please provide a title for this conversation."));
    }

    @Test
    void maybeUpdateTitle_prependsUserTurnWhenConversationStartsWithAssistant() {
        when(chatMemory.get(chatId.toString())).thenReturn(List.of(new AssistantMessage("Only an assistant message")));
        fakeChatModel.addMatcher(PromptMatcher.builder().response(VALID_TITLE).build());

        service.maybeUpdateTitle(provider, "gpt-4o", teamId, chatId);

        Prompt prompt = fakeChatModel.getInvocations().getFirst().prompt();
        List<Message> conversational = prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .toList();
        assertThat(conversational).hasSize(3);
        assertThat(conversational.get(0)).isInstanceOf(UserMessage.class);
        assertThat(conversational.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(conversational.get(2)).isInstanceOf(UserMessage.class);
    }

    @Test
    void generateInitialTitle_usesModelSummaryFromInitialPrompt() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Debug the flaky test suite")
                .response("Debug and fix the flaky test suite now")
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo("Debug and fix the flaky test suite now");
        ArgumentCaptor<ModelProvider> providerCaptor = ArgumentCaptor.forClass(ModelProvider.class);
        ArgumentCaptor<Boolean> thinkingCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(chatModelFactory).createChatModel(providerCaptor.capture(), eq("gpt-4o"), thinkingCaptor.capture());
        assertThat(providerCaptor.getValue()).isSameAs(provider);
        assertThat(thinkingCaptor.getValue()).isFalse();
        Prompt prompt = fakeChatModel.getInvocations().getFirst().prompt();
        List<Message> conversational = prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .toList();
        assertThat(conversational).hasSize(1);
        assertThat(conversational.getFirst().getText()).isEqualTo("Debug the flaky test suite");
    }

    @Test
    void generateInitialTitle_acceptsSummaryShorterThanMinWords() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Debug the flaky test suite")
                .response("Hi")
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo("Hi");
    }

    @Test
    void generateInitialTitle_acceptsSummaryUpToTwiceMaxWords() {
        String summary = String.join(" ", Collections.nCopies(60, "word"));
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Debug the flaky test suite")
                .response(summary)
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo(summary);
    }

    @Test
    void generateInitialTitle_trimsSummaryBeyondTwiceMaxWords() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Debug the flaky test suite")
                .response(String.join(" ", Collections.nCopies(61, "word")))
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo(String.join(" ", Collections.nCopies(60, "word")));
    }

    @Test
    void generateInitialTitle_fallsBackToPromptWhenLlmCallFails() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .throwsException(new RuntimeException("Simulated LLM failure"))
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo("Debug the flaky test suite");
    }

    @Test
    void generateInitialTitle_fallsBackToPromptWhenModelReturnsBlankTitle() {
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Debug the flaky test suite")
                .response("")
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo("Debug the flaky test suite");
    }

    @Test
    void generateInitialTitle_truncatesPromptFallbackToDatabaseColumnLength() {
        String longPrompt = String.join(" ", Collections.nCopies(100, "w".repeat(7)));
        assertThat(longPrompt.length()).isGreaterThan(500);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains(longPrompt.substring(0, 40))
                .response("")
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", longPrompt);

        assertThat(title).isEqualTo(longPrompt.substring(0, 500));
    }

    @Test
    void generateInitialTitle_truncatesAcceptedSummaryToDatabaseColumnLength() {
        String summary = String.join(" ", Collections.nCopies(30, "w".repeat(20)));
        assertThat(summary.length()).isGreaterThan(500);
        fakeChatModel.addMatcher(PromptMatcher.builder()
                .contains("Debug the flaky test suite")
                .response(summary)
                .build());

        String title = service.generateInitialTitle(provider, "gpt-4o", "Debug the flaky test suite");

        assertThat(title).isEqualTo(summary.substring(0, 500));
    }

    @Test
    void generateInitialTitle_returnsPromptAsIsWhenPromptIsBlank() {
        assertThat(service.generateInitialTitle(provider, "gpt-4o", null)).isNull();
        assertThat(service.generateInitialTitle(provider, "gpt-4o", "   ")).isEqualTo("   ");
        assertThat(fakeChatModel.getInvocations()).isEmpty();
    }

    @Test
    void generateInitialTitle_skipsLlmWithoutProviderOrModelName() {
        String prompt = "Debug the flaky test suite";

        assertThat(service.generateInitialTitle(null, "gpt-4o", prompt)).isEqualTo(prompt);
        assertThat(service.generateInitialTitle(provider, null, prompt)).isEqualTo(prompt);

        assertThat(fakeChatModel.getInvocations()).isEmpty();
    }
}
