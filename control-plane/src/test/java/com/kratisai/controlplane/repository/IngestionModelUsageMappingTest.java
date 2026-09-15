package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class IngestionModelUsageMappingTest {

    @Autowired
    private IngestionModelUsageRepository ingestionModelUsageRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFind_viaBatchCascade() {
        var context = testDataFactory.createUserAndTeam();
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(context.team());
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = new IngestionBatch(repo);
        IngestionModelUsage chatUsage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias");
        chatUsage.apply(new LlmUsageSnapshot(0.008, 400L, 300L, 100L));
        batch.getModelUsage().add(chatUsage);
        batch.getModelUsage()
                .add(new IngestionModelUsage(batch, ModelKind.EMBEDDING, "text-embedding-3-small", "team-embed-alias"));
        batch = ingestionBatchRepository.saveAndFlush(batch);

        entityManager.flush();
        entityManager.clear();

        IngestionBatch reloaded =
                ingestionBatchRepository.findById(batch.getId()).orElseThrow();
        assertThat(reloaded.getModelUsage()).hasSize(2);
        assertThat(reloaded.getModelUsage())
                .extracting(IngestionModelUsage::getModelKind, IngestionModelUsage::getModelIdentifier)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ModelKind.CHAT, "gpt-4o"),
                        org.assertj.core.groups.Tuple.tuple(ModelKind.EMBEDDING, "text-embedding-3-small"));
        assertThat(reloaded.getModelUsage())
                .filteredOn(usage -> usage.getModelKind() == ModelKind.CHAT)
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.getLitellmAlias()).isEqualTo("team-chat-alias");
                    assertThat(usage.getTotalSpend()).isEqualTo(0.008);
                    assertThat(usage.getTotalTokens()).isEqualTo(400L);
                });

        List<IngestionModelUsage> byTeam = ingestionModelUsageRepository.findByTeamIdAndStartedAtBetween(
                context.team().getId(),
                reloaded.getStartedAt().minusSeconds(1),
                reloaded.getStartedAt().plusSeconds(1));
        assertThat(byTeam).hasSize(2);

        // Orphan removal: clearing the collection deletes the rows
        reloaded.getModelUsage().clear();
        ingestionBatchRepository.saveAndFlush(reloaded);
        assertThat(ingestionModelUsageRepository.findAll()).isEmpty();
    }

    @Test
    void findByBatchId_returnsOnlyThatBatch() {
        var context = testDataFactory.createUserAndTeam();
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(context.team());
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = new IngestionBatch(repo);
        batch.getModelUsage().add(new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias"));
        batch = ingestionBatchRepository.saveAndFlush(batch);

        IngestionBatch other = new IngestionBatch(repo);
        other.getModelUsage().add(new IngestionModelUsage(other, ModelKind.EMBEDDING, "embed-model", "embed-alias"));
        ingestionBatchRepository.saveAndFlush(other);

        List<IngestionModelUsage> found = ingestionModelUsageRepository.findByBatchId(batch.getId());
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getModelKind()).isEqualTo(ModelKind.CHAT);
    }

    @Test
    void findByTeamIdAndStartedAtBetween_returnsOnlyTeamAndWindow() {
        var context = testDataFactory.createUserAndTeam();
        var other = testDataFactory.createUserAndTeam();
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(context.team());
        repo = repositoryRepository.save(repo);

        IngestionBatch inWindow = new IngestionBatch(repo);
        inWindow.setStartedAt(Instant.now());
        inWindow.getModelUsage().add(new IngestionModelUsage(inWindow, ModelKind.CHAT, "gpt-4o", "chat-alias"));
        ingestionBatchRepository.saveAndFlush(inWindow);

        IngestionBatch stale = new IngestionBatch(repo);
        stale.setStartedAt(Instant.parse("2020-01-01T00:00:00Z"));
        stale.getModelUsage().add(new IngestionModelUsage(stale, ModelKind.CHAT, "gpt-4o", "chat-alias"));
        ingestionBatchRepository.saveAndFlush(stale);

        Repository otherRepo = new Repository("other", "https://github.com/test/other", "main", RepositoryType.GENERIC);
        otherRepo.setTeam(other.team());
        otherRepo = repositoryRepository.save(otherRepo);
        IngestionBatch foreign = new IngestionBatch(otherRepo);
        foreign.setStartedAt(Instant.now());
        foreign.getModelUsage().add(new IngestionModelUsage(foreign, ModelKind.CHAT, "gpt-4o", "chat-alias"));
        ingestionBatchRepository.saveAndFlush(foreign);

        List<IngestionModelUsage> found = ingestionModelUsageRepository.findByTeamIdAndStartedAtBetween(
                context.team().getId(),
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600));
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getBatch().getId()).isEqualTo(inWindow.getId());
    }

    @Test
    void findByBatchIdAndModelKind_returnsMatchingRow() {
        var context = testDataFactory.createUserAndTeam();
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(context.team());
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = new IngestionBatch(repo);
        batch.getModelUsage().add(new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "chat-alias"));
        batch.getModelUsage().add(new IngestionModelUsage(batch, ModelKind.EMBEDDING, "embed-model", "embed-alias"));
        batch = ingestionBatchRepository.saveAndFlush(batch);

        assertThat(ingestionModelUsageRepository.findByBatchIdAndModelKind(batch.getId(), ModelKind.CHAT))
                .hasValueSatisfying(
                        usage -> assertThat(usage.getModelIdentifier()).isEqualTo("gpt-4o"));
        assertThat(ingestionModelUsageRepository.findByBatchIdAndModelKind(batch.getId(), ModelKind.EMBEDDING))
                .hasValueSatisfying(
                        usage -> assertThat(usage.getModelIdentifier()).isEqualTo("embed-model"));
        assertThat(ingestionModelUsageRepository.findByBatchIdAndModelKind(java.util.UUID.randomUUID(), ModelKind.CHAT))
                .isEmpty();
    }

    @Test
    void sumTotalSpend_sumsAcrossRows() {
        var context = testDataFactory.createUserAndTeam();
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(context.team());
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = new IngestionBatch(repo);
        IngestionModelUsage chatUsage = new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "chat-alias");
        chatUsage.apply(new LlmUsageSnapshot(1.5, 100L, 80L, 20L));
        batch.getModelUsage().add(chatUsage);
        IngestionModelUsage embeddingUsage =
                new IngestionModelUsage(batch, ModelKind.EMBEDDING, "embed-model", "embed-alias");
        embeddingUsage.apply(new LlmUsageSnapshot(0.75, 50L, 50L, 0L));
        batch.getModelUsage().add(embeddingUsage);
        ingestionBatchRepository.saveAndFlush(batch);

        assertThat(ingestionModelUsageRepository.sumTotalSpend()).isEqualTo(2.25);
    }

    @Test
    void deleteBatch_cascadesToModelUsage() {
        var context = testDataFactory.createUserAndTeam();
        Repository repo = new Repository("test-repo", "https://github.com/test/repo", "main", RepositoryType.GENERIC);
        repo.setTeam(context.team());
        repo = repositoryRepository.save(repo);

        IngestionBatch batch = new IngestionBatch(repo);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.getModelUsage().add(new IngestionModelUsage(batch, ModelKind.CHAT, "gpt-4o", "team-chat-alias"));
        batch = ingestionBatchRepository.saveAndFlush(batch);

        ingestionBatchRepository.delete(batch);
        ingestionBatchRepository.flush();

        assertThat(ingestionModelUsageRepository.findAll()).isEmpty();
    }
}
