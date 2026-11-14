package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class CtxRepoSynopsisMappingTest {

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private CtxRepoSynopsisRepository ctxRepoSynopsisRepository;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveFindUpdateAndDeleteRoundTrip() {
        var context = testDataFactory.createDefaultContext("Synopsis Team", "synopsis-repo");
        CtxRepoSynopsis saved = ctxRepoSynopsisRepository.saveAndFlush(new CtxRepoSynopsis(
                context.batch(),
                RepoRole.SERVICE,
                List.of("synopsis-repo"),
                "Domain: x.\n\nInterfaces: y.\n\nDeployment: z.",
                "gpt-4o"));
        UUID id = saved.getId();
        Map<String, Object> citations =
                Map.of("surfaceIds", List.of(UUID.randomUUID().toString()), "filePaths", List.of("README.md"));
        saved.setCitations(citations);
        ctxRepoSynopsisRepository.saveAndFlush(saved);
        entityManager.clear();

        CtxRepoSynopsis loaded = ctxRepoSynopsisRepository.findById(id).orElseThrow();
        assertThat(loaded.getRepoRole()).isEqualTo(RepoRole.SERVICE);
        assertThat(loaded.getKnownAs()).containsExactly("synopsis-repo");
        assertThat(loaded.getSynopsis()).startsWith("Domain:");
        assertThat(loaded.getModelRef()).isEqualTo("gpt-4o");
        assertThat(loaded.getCitations()).isEqualTo(citations);
        assertThat(loaded.getCreatedAt()).isNotNull();

        loaded.setRepoRole(RepoRole.LIBRARY);
        loaded.setSynopsis("Domain: new.\n\nInterfaces: new.\n\nDeployment: new.");
        ctxRepoSynopsisRepository.saveAndFlush(loaded);
        assertThat(ctxRepoSynopsisRepository.findById(id).orElseThrow().getRepoRole())
                .isEqualTo(RepoRole.LIBRARY);

        ctxRepoSynopsisRepository.delete(loaded);
        ctxRepoSynopsisRepository.flush();
        assertThat(ctxRepoSynopsisRepository.findById(id)).isEmpty();
    }

    @Test
    void synopsisRowIsUniquePerBatch() {
        var context = testDataFactory.createDefaultContext("Synopsis Unique Team", "unique-repo");
        ctxRepoSynopsisRepository.saveAndFlush(new CtxRepoSynopsis(
                context.batch(), RepoRole.TOOLING, List.of(), "Domain: x.\n\nInterfaces: y.\n\nDeployment: z.", null));
        assertThatThrownBy(() -> ctxRepoSynopsisRepository.saveAndFlush(new CtxRepoSynopsis(
                        context.batch(),
                        RepoRole.INFRA,
                        List.of(),
                        "Domain: x.\n\nInterfaces: y.\n\nDeployment: z.",
                        null)))
                .isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    @Test
    void deletingTheBatchCascadesTheSynopsis() {
        var context = testDataFactory.createDefaultContext("Synopsis Cascade Team", "cascade-repo");
        ctxRepoSynopsisRepository.saveAndFlush(new CtxRepoSynopsis(
                context.batch(), RepoRole.DATA, List.of(), "Domain: x.\n\nInterfaces: y.\n\nDeployment: z.", null));
        assertThat(ctxRepoSynopsisRepository.count()).isEqualTo(1);

        jdbcTemplate.update(
                "DELETE FROM ingestion_batches WHERE id = ?", context.batch().getId());
        assertThat(ctxRepoSynopsisRepository.count()).isZero();
    }
}
