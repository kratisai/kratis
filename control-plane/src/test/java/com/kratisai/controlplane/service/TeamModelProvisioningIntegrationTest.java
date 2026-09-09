package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.UseRealLlmClient;
import com.kratisai.controlplane.api.restdto.UpdateTeamRequest;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderModel;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ModelProviderRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@UseRealLlmClient
@SpringIntegrationTest
class TeamModelProvisioningIntegrationTest {

    @Autowired
    private TeamService teamService;

    @Autowired
    private LiteLLMProvisioningService provisioningService;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Autowired
    private TeamRepository teamRepository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @Test
    void updateTeam_withEmbeddingModelNotYetRegisteredOnProvider_provisionsItToLiteLLM() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        // Provider only has a CHAT model registered — the embedding model below was never
        // persisted on the provider, simulating a value picked from live model discovery.
        ModelProvider provider = new ModelProvider("Embedding Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        assertThat(provisioningService.verifyModelRegistered(provider, "text-embedding-3-small"))
                .isFalse();

        UpdateTeamRequest request =
                new UpdateTeamRequest(null, null, null, null, null, provider.getId(), "text-embedding-3-small");
        teamService.updateTeam(ctx.user().getId(), team.getId(), request);

        assertThat(provisioningService.verifyModelRegistered(provider, "text-embedding-3-small"))
                .isTrue();

        ModelProvider reloaded =
                modelProviderRepository.findById(provider.getId()).orElseThrow();
        assertThat(reloaded.getModels())
                .anyMatch(m -> m.getModelName().equals("text-embedding-3-small") && m.getKind() == ModelKind.EMBEDDING);
    }

    @Test
    void updateTeam_withIngestionModelNotYetRegisteredOnProvider_provisionsItToLiteLLM() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider = new ModelProvider("Ingestion Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(List.of());
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);

        assertThat(provisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .isFalse();

        UpdateTeamRequest request = new UpdateTeamRequest(null, null, null, provider.getId(), "gpt-4o", null, null);
        teamService.updateTeam(ctx.user().getId(), team.getId(), request);

        assertThat(provisioningService.verifyModelRegistered(provider, "gpt-4o"))
                .isTrue();

        ModelProvider reloaded =
                modelProviderRepository.findById(provider.getId()).orElseThrow();
        assertThat(reloaded.getModels())
                .anyMatch(m -> m.getModelName().equals("gpt-4o") && m.getKind() == ModelKind.CHAT);
    }

    @Test
    void updateTeam_withModelAlreadyRegistered_doesNotDuplicateProviderModelEntry() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = teamRepository.findById(ctx.team().getId()).orElseThrow();

        ModelProvider provider = new ModelProvider("Existing Provider", ProviderType.OPENAI, "sk-fake-key", null);
        provider.setModels(List.of(new ProviderModel("text-embedding-3-small", ModelKind.EMBEDDING)));
        provider.setTeam(team);
        provider = modelProviderRepository.save(provider);
        provisioningService.provisionModel(provider);

        UpdateTeamRequest request =
                new UpdateTeamRequest(null, null, null, null, null, provider.getId(), "text-embedding-3-small");
        teamService.updateTeam(ctx.user().getId(), team.getId(), request);

        ModelProvider reloaded =
                modelProviderRepository.findById(provider.getId()).orElseThrow();
        assertThat(reloaded.getModels()).hasSize(1);
        assertThat(provisioningService.verifyModelRegistered(provider, "text-embedding-3-small"))
                .isTrue();
    }
}
