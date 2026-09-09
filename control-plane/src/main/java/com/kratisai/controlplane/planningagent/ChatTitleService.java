package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.ChatService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates and evolves concise, declarative chat titles using a quick one-shot LLM call.
 *
 * <p>The request is deliberately lightweight: a non-streaming {@link ChatClient#call()} with
 * thinking disabled and no tools. Titles follow ASD-STE100 Simplified Technical English. The
 * evolved title must stay within a configurable word-count window; the initial title prefers any
 * usable summary over the raw prompt.
 */
@Service
public class ChatTitleService {

    private static final Logger logger = LoggerFactory.getLogger(ChatTitleService.class);

    private static final int DB_TITLE_MAX_LENGTH = 500;

    private static final String TITLE_DIRECTIVE = "Please provide a title for this conversation.";

    static final String SYSTEM_PROMPT_TEMPLATE = """
            You write titles for software development conversations.

            Rules:
            - Output only the title on a single line. Do not explain yourself.
            - The title must be a declarative phrase written in ASD-STE100 Simplified Technical English: short, clear, and unambiguous.
            - The title must be between %d and %d words long.
            - Do not use quotes, questions, leading articles, or trailing punctuation.
            """;

    private final ChatModelFactory chatModelFactory;
    private final ChatMemory chatMemory;
    private final ChatService chatService;
    private final int minWords;
    private final int maxWords;
    private final int maxMessages;

    public ChatTitleService(
            ChatModelFactory chatModelFactory,
            ChatMemory chatMemory,
            ChatService chatService,
            @Value("${kratis.titles.min-words:8}") int minWords,
            @Value("${kratis.titles.max-words:30}") int maxWords,
            @Value("${kratis.titles.max-messages:10}") int maxMessages) {
        this.chatModelFactory = chatModelFactory;
        this.chatMemory = chatMemory;
        this.chatService = chatService;
        this.minWords = minWords;
        this.maxWords = maxWords;
        this.maxMessages = maxMessages;
    }

    /**
     * Regenerate a chat title from its recent conversation history. Skips chats that are not
     * persisted, chats without messages, unchanged titles, and titles the model produced outside
     * the configured word-count window. Never throws; failures are logged and leave the title
     * intact.
     */
    public void maybeUpdateTitle(ModelProvider provider, String modelName, UUID teamId, UUID chatId) {
        if (provider == null || modelName == null || teamId == null || chatId == null) {
            return;
        }
        Optional<String> currentTitle = chatService.findTitle(chatId);
        if (currentTitle.isEmpty()) {
            logger.warn("No existing title - not updating.  There should always be an existing title....");
            return;
        }
        List<Message> messages = chatMemory.get(chatId.toString());
        if (messages.isEmpty()) {
            logger.warn("No messages for updating title.  There should always be messages....");
            return;
        }
        String title;
        try {
            title = requestTitle(provider, modelName, excerpt(messages));
        } catch (Exception e) {
            logger.warn(
                    "Failed to generate title for chat {} using provider {} model {}",
                    chatId,
                    provider.getId(),
                    modelName,
                    e);
            return;
        }
        if (!isWithinLimits(title) || title.equals(currentTitle.get())) {
            return;
        }
        if (chatService.updateTitle(chatId, teamId, title)) {
            logger.info("Updated title for chat {} to '{}'", chatId, title);
        }
    }

    /**
     * Generate the initial title from the very first user prompt. The model's summary is preferred
     * over the raw prompt: any non-empty summary is accepted, output longer than twice the
     * configured maximum word count is trimmed to that limit, and only a genuine failure (empty
     * output, LLM error, or missing provider/model) falls back to the prompt. Never throws.
     */
    public String generateInitialTitle(ModelProvider provider, String modelName, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        if (provider == null || modelName == null) {
            return truncateToDbLength(prompt);
        }
        String title;
        try {
            title = requestTitle(provider, modelName, List.of(new UserMessage(prompt)));
        } catch (Exception e) {
            logger.warn("Failed to generate initial title using provider {} model {}", provider.getId(), modelName, e);
            return truncateToDbLength(prompt);
        }
        if (title.isEmpty()) {
            return truncateToDbLength(prompt);
        }
        if (wordCount(title) > maxWords * 2) {
            title = truncateToWordCount(title, maxWords * 2);
        }
        return truncateToDbLength(title);
    }

    private static String truncateToDbLength(String text) {
        return text.length() > DB_TITLE_MAX_LENGTH ? text.substring(0, DB_TITLE_MAX_LENGTH) : text;
    }

    private static String truncateToWordCount(String title, int maxWordCount) {
        String[] words = title.split("\\s+");
        if (words.length <= maxWordCount) {
            return title;
        }
        return String.join(" ", Arrays.copyOf(words, maxWordCount));
    }

    /** One-shot, non-thinking, non-streaming LLM call that returns the sanitized candidate title. */
    private String requestTitle(ModelProvider provider, String modelName, List<Message> excerpt) {
        ChatModel chatModel = chatModelFactory.createChatModel(provider, modelName, false);
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        String response = chatClient
                .prompt()
                .system(SYSTEM_PROMPT_TEMPLATE.formatted(minWords, maxWords))
                .messages(excerpt)
                .call()
                .content();
        return sanitizeTitle(response);
    }

    /** Keep only the most recent {@code maxMessages} user/assistant messages. */
    private List<Message> excerpt(List<Message> messages) {
        List<Message> conversational = messages.stream()
                .filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
                .toList();
        if (conversational.size() > maxMessages) {
            conversational =
                    List.copyOf(conversational.subList(conversational.size() - maxMessages, conversational.size()));
        }
        return closeConversationWithUserTurns(conversational);
    }

    private List<Message> closeConversationWithUserTurns(List<Message> conversational) {
        if (conversational.isEmpty()) {
            return conversational;
        }
        List<Message> normalized = new ArrayList<>(conversational);
        if (!(normalized.getFirst() instanceof UserMessage)) {
            normalized.addFirst(new UserMessage(TITLE_DIRECTIVE));
        }
        if (!(normalized.getLast() instanceof UserMessage)) {
            normalized.add(new UserMessage(TITLE_DIRECTIVE));
        }
        return List.copyOf(normalized);
    }

    boolean isWithinLimits(String title) {
        return title != null
                && wordCount(title) >= minWords
                && wordCount(title) <= (maxWords * 2)
                && title.length() <= DB_TITLE_MAX_LENGTH;
    }

    static int wordCount(String title) {
        if (title == null || title.isBlank()) {
            return 0;
        }
        return title.strip().split("\\s+").length;
    }

    static String sanitizeTitle(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String line = raw.strip().lines().findFirst().orElse("").trim();
        line = line.replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
        return line.replaceAll("\\s+", " ");
    }
}
