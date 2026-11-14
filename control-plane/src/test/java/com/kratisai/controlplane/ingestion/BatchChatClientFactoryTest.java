package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.ModelCostEntry;
import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderModel;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.service.ChatModelFactory;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.ProviderPricing;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BatchChatClientFactoryTest {

    @Test
    void derivesPromptCharacterBudgetFromDiscoveredContextWindowOrLiteLLMCostMap() {
        LiteLLMClient liteLLMClient = mock(LiteLLMClient.class);
        LiteLLMProperties properties = new LiteLLMProperties();
        properties.getReconciliation().setEnabled(false);
        LiteLLMProvisioningService provisioningService = new LiteLLMProvisioningService(
                liteLLMClient,
                mock(ModelProviderRepository.class),
                mock(TeamRepository.class),
                properties,
                mock(ProviderPricing.class));
        BatchChatClientFactory factory = new BatchChatClientFactory(mock(ChatModelFactory.class), provisioningService);

        // 1) ProviderModel has an explicit discovered contextWindowTokens -> zero LiteLLM HTTP calls.
        ModelProvider anthropic = new ModelProvider();
        ReflectionTestUtils.setField(anthropic, "id", UUID.randomUUID());
        anthropic.setProviderType(ProviderType.ANTHROPIC);
        anthropic.setModels(List.of(new ProviderModel("claude-sonnet-5-5", null, ModelKind.CHAT, 1_000_000L)));
        assertThat(factory.maxPromptCharsFor(batchFor(anthropic, "claude-sonnet-5-5")))
                .isEqualTo(600_000);

        // 2) ProviderModel contextWindowTokens is null (e.g. Bedrock minimax) -> resolves via LiteLLM cost map &
        // caches.
        ModelProvider bedrock = new ModelProvider();
        ReflectionTestUtils.setField(bedrock, "id", UUID.randomUUID());
        bedrock.setProviderType(ProviderType.BEDROCK);
        bedrock.setBaseUrl("https://bedrock-runtime.eu-west-2.amazonaws.com");
        bedrock.setModels(List.of(new ProviderModel("minimax.minimax-m2.5", null, ModelKind.CHAT, null)));
        when(liteLLMClient.modelCostMap())
                .thenReturn(Map.of("minimax.minimax-m2.5", new ModelCostEntry(3e-7, 1.2e-6, 196_000L, null)));

        IngestionBatch bedrockBatch = batchFor(bedrock, "minimax.minimax-m2.5");
        assertThat(factory.maxPromptCharsFor(bedrockBatch)).isEqualTo(294_000);
        assertThat(factory.maxPromptCharsFor(bedrockBatch)).isEqualTo(294_000);
        verify(liteLLMClient, times(1)).modelCostMap();

        // 3) Small 32k context model scales down proportionally; unknown self-hosted model falls back to 128k default.
        assertThat(BatchChatClientFactory.deriveMaxPromptChars(32_000L)).isEqualTo(48_000);
        assertThat(BatchChatClientFactory.deriveMaxPromptChars(8_000L)).isEqualTo(32_000);
        ModelProvider unknown = new ModelProvider();
        ReflectionTestUtils.setField(unknown, "id", UUID.randomUUID());
        unknown.setProviderType(ProviderType.OTHER);
        unknown.setModels(List.of(new ProviderModel("local-llama", "llama-custom", ModelKind.CHAT, null)));
        assertThat(factory.maxPromptCharsFor(batchFor(unknown, "local-llama"))).isEqualTo(192_000);
    }

    private static IngestionBatch batchFor(ModelProvider provider, String modelName) {
        Team team = new Team("team", "team-slug");
        team.setIngestionProvider(provider);
        team.setIngestionModel(modelName);
        Repository repository =
                new Repository("repo", "https://example.invalid/repo.git", "main", RepositoryType.GENERIC);
        repository.setTeam(team);
        return new IngestionBatch(repository);
    }
}
