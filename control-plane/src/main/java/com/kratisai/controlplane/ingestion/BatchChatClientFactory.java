package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Builds the per-batch chat client that routes through the batch's LiteLLM virtual key, so every
 * token the synopsis and inference calls use lands on the batch's own usage rows.
 */
@Component
public class BatchChatClientFactory {
    static final int CHARS_PER_TOKEN = 3;
    static final int MIN_PROMPT_CHARS = 32_000;
    static final int MAX_PROMPT_CHARS_CAP = 600_000;

    private final ChatModelFactory chatModelFactory;
    private final LiteLLMProvisioningService litellmProvisioningService;

    public BatchChatClientFactory(
            ChatModelFactory chatModelFactory, LiteLLMProvisioningService litellmProvisioningService) {
        this.chatModelFactory = chatModelFactory;
        this.litellmProvisioningService = litellmProvisioningService;
    }

    public ChatClient chatClientFor(IngestionBatch batch) {
        Team team = batch.getRepository().getTeam();
        ModelProvider modelProvider = team.getIngestionProvider();
        String modelName = team.getIngestionModel();
        String litellmModelName = litellmProvisioningService.buildLiteLLMModelName(modelProvider, modelName);
        String virtualKey = batch.modelUsageFor(ModelKind.CHAT)
                .map(IngestionModelUsage::getVirtualKey)
                .orElse(null);
        return ChatClient.builder(
                        chatModelFactory.createChatModelViaLiteLLM(modelProvider, litellmModelName, virtualKey, false))
                .build();
    }

    public String modelRefFor(IngestionBatch batch) {
        return batch.getRepository().getTeam().getIngestionModel();
    }

    public int maxPromptCharsFor(IngestionBatch batch) {
        Team team = batch.getRepository().getTeam();
        long contextWindowTokens = litellmProvisioningService.resolveContextWindowTokens(
                team.getIngestionProvider(), team.getIngestionModel());
        return deriveMaxPromptChars(contextWindowTokens);
    }

    public static int deriveMaxPromptChars(long contextWindowTokens) {
        long usableInputTokens = Math.max(1L, contextWindowTokens) / 2;
        long derivedChars = usableInputTokens * CHARS_PER_TOKEN;
        return (int) Math.clamp(derivedChars, MIN_PROMPT_CHARS, MAX_PROMPT_CHARS_CAP);
    }
}
