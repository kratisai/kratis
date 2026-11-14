package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.RelationState;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import jakarta.persistence.PersistenceException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class CtxRepoRelationMappingTest {

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private CtxRepoRelationRepository ctxRepoRelationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestDataFactory.TestContext contextA;
    private TestDataFactory.TestContext contextB;

    @BeforeEach
    void setUp() {
        contextA = testDataFactory.createDefaultContext("Relation Team", "repo-a");
        var repository = testDataFactory.createRepository(
                contextA.team(), "repo-b", "https://example.com/repo-b.git", "main", null);
        contextB = new TestDataFactory.TestContext(
                contextA.team(), null, repository, testDataFactory.createBatchForRepository(repository), null);
    }

    /** Saves one CALLS row for the pair of batches, with the columns in SQL canonical order. */
    private CtxRepoRelation saveRelation(IngestionBatch batchOne, IngestionBatch batchTwo) {
        boolean swapped = CtxRepoRelation.compareBySqlOrder(
                        batchOne.getRepository().getId(),
                        batchTwo.getRepository().getId())
                > 0;
        IngestionBatch batchA = swapped ? batchTwo : batchOne;
        IngestionBatch batchB = swapped ? batchOne : batchTwo;
        return ctxRepoRelationRepository.saveAndFlush(new CtxRepoRelation(
                contextA.team().getId(), batchA, batchB, Nature.CALLS, Direction.A_TO_B, Ceiling.CONFIRMED));
    }

    @Test
    void saveFindAndDeleteRoundTrip() {
        CtxRepoRelation saved = saveRelation(contextA.batch(), contextB.batch());
        UUID id = saved.getId();

        CtxRepoRelation loaded = ctxRepoRelationRepository.findById(id).orElseThrow();
        assertThat(loaded.getTeamId()).isEqualTo(contextA.team().getId());
        assertThat(loaded.getNature()).isEqualTo(Nature.CALLS);
        assertThat(loaded.getDirection()).isEqualTo(Direction.A_TO_B);
        assertThat(loaded.getConfidence()).isEqualTo(Ceiling.CONFIRMED);
        assertThat(loaded.getState()).isEqualTo(RelationState.CURRENT);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getBatchA().getId())
                .isIn(contextA.batch().getId(), contextB.batch().getId());
        assertThat(loaded.getBatchB().getId())
                .isIn(contextA.batch().getId(), contextB.batch().getId());
        assertThat(loaded.getBatchA().getId()).isNotEqualTo(loaded.getBatchB().getId());

        ctxRepoRelationRepository.delete(loaded);
        ctxRepoRelationRepository.flush();
        assertThat(ctxRepoRelationRepository.findById(id)).isEmpty();
    }

    @Test
    void constructorRejectsNonCanonicalPairs() {
        IngestionBatch batchA = contextA.batch();
        IngestionBatch batchB = contextB.batch();
        boolean repoAIsLower = CtxRepoRelation.compareBySqlOrder(
                        batchA.getRepository().getId(), batchB.getRepository().getId())
                < 0;

        new CtxRepoRelation(
                contextA.team().getId(),
                repoAIsLower ? batchA : batchB,
                repoAIsLower ? batchB : batchA,
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.CONFIRMED);

        assertThatThrownBy(() -> new CtxRepoRelation(
                        contextA.team().getId(),
                        repoAIsLower ? batchB : batchA,
                        repoAIsLower ? batchA : batchB,
                        Nature.CALLS,
                        Direction.A_TO_B,
                        Ceiling.CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository_a_id");
    }

    @Test
    void databaseCheckRejectsInvertedColumns() {
        IngestionBatch batchA = contextA.batch();
        IngestionBatch batchB = contextB.batch();
        UUID repoA = batchA.getRepository().getId();
        UUID repoB = batchB.getRepository().getId();
        boolean repoAIsLower = CtxRepoRelation.compareBySqlOrder(repoA, repoB) < 0;

        CtxRepoRelation relation = new CtxRepoRelation(
                contextA.team().getId(),
                repoAIsLower ? batchA : batchB,
                repoAIsLower ? batchB : batchA,
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.CONFIRMED);
        // Invert the columns to violate repository_a_id < repository_b_id; the check aborts the
        // test transaction, so nothing more can be asserted in it.
        writeRepositoryColumns(relation, repoAIsLower ? repoB : repoA, repoAIsLower ? repoA : repoB);

        assertThatThrownBy(() -> ctxRepoRelationRepository.saveAndFlush(relation))
                .isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    private static void writeRepositoryColumns(CtxRepoRelation relation, UUID repositoryAId, UUID repositoryBId) {
        try {
            var fieldA = CtxRepoRelation.class.getDeclaredField("repositoryAId");
            fieldA.setAccessible(true);
            fieldA.set(relation, repositoryAId);
            var fieldB = CtxRepoRelation.class.getDeclaredField("repositoryBId");
            fieldB.setAccessible(true);
            fieldB.set(relation, repositoryBId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void duplicatePairAndNatureViolatesTheUniqueConstraint() {
        saveRelation(contextA.batch(), contextB.batch());
        // The second row for the same triplet trips uq_ctx_repo_relations_pair_nature and aborts
        // the test transaction, so nothing more can be asserted in it.
        assertThatThrownBy(() -> saveRelation(contextA.batch(), contextB.batch()))
                .isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    @Test
    void deletingABatchCascadesItsRelationRows() {
        saveRelation(contextA.batch(), contextB.batch());
        boolean repoAIsLower = CtxRepoRelation.compareBySqlOrder(
                        contextA.repository().getId(), contextB.repository().getId())
                < 0;
        assertThat(ctxRepoRelationRepository.count()).isEqualTo(1);

        // Raw delete: the schema's ON DELETE CASCADE does what the flush-ordered entity delete would not.
        IngestionBatch deletedBatch = repoAIsLower ? contextA.batch() : contextB.batch();
        jdbcTemplate.update("DELETE FROM ingestion_batches WHERE id = ?", deletedBatch.getId());
        assertThat(ctxRepoRelationRepository.count()).isZero();
    }

    @Test
    void sqlUuidOrderingDisagreesWithJavaOnTheHighBit() {
        UUID highBit = UUID.fromString("f0555555-1111-2222-3333-444444444444");
        UUID lowBit = UUID.fromString("7f055555-1111-2222-3333-444444444444");

        assertThat(highBit.compareTo(lowBit)).isNegative();
        assertThat(CtxRepoRelation.compareBySqlOrder(highBit, lowBit)).isPositive();
        assertThat(CtxRepoRelation.compareBySqlOrder(lowBit, highBit)).isNegative();
        assertThat(CtxRepoRelation.compareBySqlOrder(lowBit, lowBit)).isZero();
    }
}
