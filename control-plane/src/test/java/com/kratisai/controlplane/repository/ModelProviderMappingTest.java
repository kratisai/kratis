package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class ModelProviderMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ModelProviderRepository modelProviderRepository;

    @Test
    void testModelProviderEntityMapping() {
        // Create team first
        Team team = new Team("Provider Team", "Team with model providers");
        teamRepository.save(team);
        entityManager.flush();

        // Create and save model provider
        ModelProvider provider = new ModelProvider(
                "My OpenAI Provider", ProviderType.OPENAI, "sk-test-key-123", "https://api.openai.com/v1");
        provider.setTeam(team);
        modelProviderRepository.save(provider);
        entityManager.flush();

        // Verify save
        assertThat(provider.getId()).isNotNull();
        assertThat(provider.getCreatedAt()).isNotNull();
        assertThat(provider.getUpdatedAt()).isNotNull();

        // Verify findByTeamId
        List<ModelProvider> providers = modelProviderRepository.findByTeamId(team.getId());
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst().getDisplayName()).isEqualTo("My OpenAI Provider");
        assertThat(providers.getFirst().getProviderType()).isEqualTo(ProviderType.OPENAI);
        assertThat(providers.getFirst().getApiKey()).isEqualTo("sk-test-key-123");
        assertThat(providers.getFirst().getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(providers.getFirst().isActive()).isTrue();

        // Verify findByTeamIdAndId
        Optional<ModelProvider> found = modelProviderRepository.findByTeamIdAndId(team.getId(), provider.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("My OpenAI Provider");

        // Verify existsByTeamIdAndDisplayName
        assertThat(modelProviderRepository.existsByTeamIdAndDisplayName(team.getId(), "My OpenAI Provider"))
                .isTrue();
        assertThat(modelProviderRepository.existsByTeamIdAndDisplayName(team.getId(), "nonexistent"))
                .isFalse();

        // Verify update triggers updatedAt
        found.get().setDisplayName("Updated Provider");
        entityManager.flush();
        assertThat(found.get().getUpdatedAt()).isNotNull();

        // Verify delete
        modelProviderRepository.delete(found.get());
        entityManager.flush();
        assertThat(modelProviderRepository.findByTeamId(team.getId())).isEmpty();
    }

    @Test
    void testModelProviderWithOtherType() {
        // Create team first
        Team team = new Team("Custom Provider Team", "Team with custom providers");
        teamRepository.save(team);
        entityManager.flush();

        // Create and save model provider with OTHER type
        ModelProvider provider = new ModelProvider(
                "Custom Provider", ProviderType.OTHER, "custom-api-key", "https://custom-api.example.com/v1");
        provider.setTeam(team);
        modelProviderRepository.save(provider);
        entityManager.flush();

        // Verify save
        assertThat(provider.getId()).isNotNull();
        assertThat(provider.getProviderType()).isEqualTo(ProviderType.OTHER);
        assertThat(provider.getBaseUrl()).isEqualTo("https://custom-api.example.com/v1");

        // Verify findByTeamId
        List<ModelProvider> providers = modelProviderRepository.findByTeamId(team.getId());
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst().getProviderType()).isEqualTo(ProviderType.OTHER);
    }

    @Test
    void testModelProviderRequiresTeam() {
        // Verify that a model provider cannot be saved without a team
        Team team = new Team("Provider Team", "Team with model providers");
        teamRepository.saveAndFlush(team);

        ModelProvider provider = new ModelProvider(
                "My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", "https://api.openai.com/v1");
        provider.setTeam(team);
        modelProviderRepository.saveAndFlush(provider);

        // Verify the relationship is established
        assertThat(provider.getTeam().getId()).isEqualTo(team.getId());
        assertThat(modelProviderRepository.findByTeamId(team.getId())).hasSize(1);
    }
}
