package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxArchitecturePattern;
import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.CtxEdge;
import com.kratisai.controlplane.model.CtxEmbedding;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.CtxNodeDimension;
import com.kratisai.controlplane.model.CtxRepoRelation;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.CtxWikiPage;
import com.kratisai.controlplane.model.DimensionCategory;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionBatchLog;
import com.kratisai.controlplane.model.IngestionModelUsage;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import com.kratisai.controlplane.model.RepoRole;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxArchitecturePatternRepository;
import com.kratisai.controlplane.repository.CtxDimensionRepository;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxNodeDimensionRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.CtxRepoRelationRepository;
import com.kratisai.controlplane.repository.CtxRepoSynopsisRepository;
import com.kratisai.controlplane.repository.CtxSurfaceAttributeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.IngestionBatchLogRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.IngestionModelUsageRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

@SpringIntegrationTest
class IngestionQueueManagerComponentTest {

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    @Autowired
    private CtxEdgeRepository ctxEdgeRepository;

    @Autowired
    private CtxEmbeddingRepository ctxEmbeddingRepository;

    @Autowired
    private CtxWikiPageRepository ctxWikiPageRepository;

    @Autowired
    private CtxArchitecturePatternRepository ctxArchitecturePatternRepository;

    @Autowired
    private CtxSurfaceRepository ctxSurfaceRepository;

    @Autowired
    private CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository;

    @Autowired
    private CtxDimensionRepository ctxDimensionRepository;

    @Autowired
    private CtxNodeDimensionRepository ctxNodeDimensionRepository;

    @Autowired
    private CtxRepoRelationRepository ctxRepoRelationRepository;

    @Autowired
    private CtxRepoSynopsisRepository ctxRepoSynopsisRepository;

    @Autowired
    private IngestionBatchLogRepository ingestionBatchLogRepository;

    @Autowired
    private IngestionModelUsageRepository ingestionModelUsageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private IngestionWorker ingestionWorker;
    private IngestionQueueManager queueManager;
    private Team team;
    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        ingestionWorker = mock(IngestionWorker.class);
        queueManager = queueManager(ctxEdgeRepository);

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        team = ctx.team();
        repository = new Repository(
                "recovery-repo", "https://github.com/kratisai/recovery-repo.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);
        repository = repositoryRepository.saveAndFlush(repository);
    }

    private IngestionQueueManager queueManager(CtxEdgeRepository edgeRepository) {
        return new IngestionQueueManager(
                ingestionBatchRepository,
                ingestionWorker,
                transactionManager,
                ctxNodeRepository,
                edgeRepository,
                ctxDimensionRepository,
                ctxNodeDimensionRepository,
                ctxEmbeddingRepository,
                ctxWikiPageRepository,
                ctxArchitecturePatternRepository,
                ctxSurfaceRepository,
                ctxRepoRelationRepository,
                ctxRepoSynopsisRepository,
                ingestionBatchLogRepository);
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void recoverAndResubmit_clearsAllOrphanedBatchDataAndResubmits() {
        IngestionBatch batch = new IngestionBatch(repository);
        batch.setStatus(IngestionStatus.PROCESSING);
        IngestionBatch savedBatch = ingestionBatchRepository.saveAndFlush(batch);
        UUID teamId = team.getId();

        CtxNode sourceNode = ctxNodeRepository.saveAndFlush(
                new CtxNode(savedBatch, teamId, repository.getName(), NodeType.FILE, "src/A.java"));
        CtxNode targetNode = ctxNodeRepository.saveAndFlush(
                new CtxNode(savedBatch, teamId, repository.getName(), NodeType.FILE, "src/B.java"));
        UUID sourceNodeId = sourceNode.getId();
        UUID targetNodeId = targetNode.getId();

        CtxEdge edge = ctxEdgeRepository.saveAndFlush(
                new CtxEdge(savedBatch, teamId, sourceNode, targetNode, RelationType.CALLS));
        UUID edgeId = edge.getId();

        CtxSurface surface = ctxSurfaceRepository.saveAndFlush(new CtxSurface(
                savedBatch, repository.getId(), teamId, SurfaceRole.PROVIDES, "GET /health", SurfaceDetection.CMM));
        CtxSurfaceAttribute attribute = ctxSurfaceAttributeRepository.saveAndFlush(
                new CtxSurfaceAttribute(surface, AttributeKind.IDENTIFIER, "/health", SurfaceDetection.CMM));

        CtxDimension dimension = ctxDimensionRepository.saveAndFlush(new CtxDimension(
                savedBatch, teamId, DimensionCategory.ARCHETYPE, "API", "API endpoints", List.of("src/**/*.java")));
        ctxNodeDimensionRepository.saveAndFlush(new CtxNodeDimension(sourceNode, dimension, 0.9));

        CtxWikiPage rootPage = ctxWikiPageRepository.saveAndFlush(
                new CtxWikiPage(savedBatch, teamId, repository.getName(), null, "root", "Root", 0, "root content"));
        CtxWikiPage childPage = ctxWikiPageRepository.saveAndFlush(new CtxWikiPage(
                savedBatch, teamId, repository.getName(), rootPage, "child", "Child", 1, "child content"));
        CtxEmbedding embedding = ctxEmbeddingRepository.saveAndFlush(
                new CtxEmbedding(savedBatch, team, childPage, "chunk", new float[] {0.1f, 0.2f}));

        CtxArchitecturePattern pattern = ctxArchitecturePatternRepository.saveAndFlush(
                new CtxArchitecturePattern(savedBatch, teamId, "Layered", "Layered architecture", List.of(sourceNode)));

        UUID logId = ingestionBatchLogRepository
                .saveAndFlush(new IngestionBatchLog(savedBatch, teamId, "INFO", "CLONE", "cloned"))
                .getId();

        Repository peerRepo =
                new Repository("peer-repo", "https://github.com/kratisai/peer-repo.git", "main", RepositoryType.GITHUB);
        peerRepo.setTeam(team);
        peerRepo = repositoryRepository.saveAndFlush(peerRepo);
        IngestionBatch peerBatch = new IngestionBatch(peerRepo);
        peerBatch.setStatus(IngestionStatus.SUCCESS);
        peerBatch = ingestionBatchRepository.saveAndFlush(peerBatch);
        CtxNode peerNodeA = ctxNodeRepository.saveAndFlush(
                new CtxNode(peerBatch, teamId, peerRepo.getName(), NodeType.FILE, "src/PeerA.java"));
        CtxNode peerNodeB = ctxNodeRepository.saveAndFlush(
                new CtxNode(peerBatch, teamId, peerRepo.getName(), NodeType.FILE, "src/PeerB.java"));
        CtxEdge peerEdge = ctxEdgeRepository.saveAndFlush(
                new CtxEdge(peerBatch, teamId, peerNodeA, peerNodeB, RelationType.CALLS));
        CtxDimension peerDimension = ctxDimensionRepository.saveAndFlush(new CtxDimension(
                peerBatch, teamId, DimensionCategory.ARCHETYPE, "PeerAPI", "Peer API", List.of("src/**/*.java")));

        boolean orphanFirst = CtxRepoRelation.compareBySqlOrder(repository.getId(), peerRepo.getId()) < 0;
        CtxRepoRelation relation = ctxRepoRelationRepository.saveAndFlush(new CtxRepoRelation(
                teamId,
                orphanFirst ? savedBatch : peerBatch,
                orphanFirst ? peerBatch : savedBatch,
                Nature.CALLS,
                Direction.A_TO_B,
                Ceiling.CONFIRMED));
        CtxRepoSynopsis synopsis = ctxRepoSynopsisRepository.saveAndFlush(
                new CtxRepoSynopsis(savedBatch, RepoRole.SERVICE, List.of("recovery-repo"), "synopsis", "test-model"));
        IngestionModelUsage usage = ingestionModelUsageRepository.saveAndFlush(
                new IngestionModelUsage(savedBatch, ModelKind.CHAT, "gpt-4o", "alias"));

        IngestionBatch queuedBatch = new IngestionBatch(repository);
        queuedBatch.setStatus(IngestionStatus.QUEUED);
        queuedBatch = ingestionBatchRepository.saveAndFlush(queuedBatch);

        IngestionBatch failedBatch = new IngestionBatch(repository);
        failedBatch.setStatus(IngestionStatus.FAILED);
        failedBatch = ingestionBatchRepository.saveAndFlush(failedBatch);

        queueManager.recoverAndResubmit();

        IngestionBatch resetBatch =
                ingestionBatchRepository.findById(savedBatch.getId()).orElseThrow();
        assertThat(resetBatch.getStatus()).isEqualTo(IngestionStatus.QUEUED);
        assertThat(resetBatch.getErrorMessage()).contains("Reset on startup");

        assertThat(ctxNodeRepository.findById(sourceNodeId)).isEmpty();
        assertThat(ctxNodeRepository.findById(targetNodeId)).isEmpty();
        assertThat(ctxEdgeRepository.findById(edgeId)).isEmpty();
        assertThat(ctxSurfaceRepository.findByBatchId(savedBatch.getId())).isEmpty();
        assertThat(ctxSurfaceAttributeRepository.findById(attribute.getId())).isEmpty();
        assertThat(ctxWikiPageRepository.findById(rootPage.getId())).isEmpty();
        assertThat(ctxWikiPageRepository.findById(childPage.getId())).isEmpty();
        assertThat(ctxEmbeddingRepository.findById(embedding.getId())).isEmpty();
        assertThat(ctxArchitecturePatternRepository.findById(pattern.getId())).isEmpty();
        assertThat(ingestionBatchLogRepository.findById(logId)).isEmpty();
        assertThat(ctxRepoRelationRepository.findById(relation.getId())).isEmpty();
        assertThat(ctxRepoSynopsisRepository.findById(synopsis.getId())).isEmpty();
        assertThat(ctxNodeDimensionRepository.findByDimensionId(dimension.getId()))
                .isEmpty();
        assertThat(ctxDimensionRepository.findByBatchId(savedBatch.getId())).isEmpty();
        assertThat(ingestionModelUsageRepository.findById(usage.getId())).isPresent();

        assertThat(ctxNodeRepository.findById(peerNodeA.getId())).isPresent();
        assertThat(ctxEdgeRepository.findById(peerEdge.getId())).isPresent();
        assertThat(ctxDimensionRepository.findById(peerDimension.getId())).isPresent();

        verify(ingestionWorker).runIngestion(savedBatch.getId());
        verify(ingestionWorker).runIngestion(queuedBatch.getId());
        verify(ingestionWorker, never()).runIngestion(peerBatch.getId());
        verify(ingestionWorker, never()).runIngestion(failedBatch.getId());
    }

    @Test
    void recoverAndResubmit_multipleOrphanedBatchesClearsEveryEdgeWithoutStaleDeletes() {
        Orphan first = createOrphan("src/FirstA.java", "src/FirstB.java");
        Orphan second = createOrphan("src/SecondA.java", "src/SecondB.java");

        queueManager.recoverAndResubmit();

        assertThat(ingestionBatchRepository
                        .findById(first.batchId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(IngestionStatus.QUEUED);
        assertThat(ingestionBatchRepository
                        .findById(second.batchId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(IngestionStatus.QUEUED);
        assertThat(ctxEdgeRepository.findById(first.edgeId())).isEmpty();
        assertThat(ctxEdgeRepository.findById(second.edgeId())).isEmpty();
        assertThat(ctxNodeRepository.findById(first.sourceNodeId())).isEmpty();
        assertThat(ctxNodeRepository.findById(first.targetNodeId())).isEmpty();
        assertThat(ctxNodeRepository.findById(second.sourceNodeId())).isEmpty();
        assertThat(ctxNodeRepository.findById(second.targetNodeId())).isEmpty();
        verify(ingestionWorker).runIngestion(first.batchId());
        verify(ingestionWorker).runIngestion(second.batchId());
    }

    @Test
    void recoverAndResubmit_marksFailedCleanupFailedAndStillRecoversOtherBatches() {
        Orphan failing = createOrphan("src/FailingA.java", "src/FailingB.java");
        Orphan healthy = createOrphan("src/HealthyA.java", "src/HealthyB.java");

        CtxEdgeRepository edgeRepository =
                mock(CtxEdgeRepository.class, AdditionalAnswers.delegatesTo(ctxEdgeRepository));
        doThrow(new IllegalStateException("simulated delete failure"))
                .when(edgeRepository)
                .deleteByBatchId(failing.batchId());
        IngestionQueueManager resilientQueueManager = queueManager(edgeRepository);

        resilientQueueManager.recoverAndResubmit();

        IngestionBatch failedBatch =
                ingestionBatchRepository.findById(failing.batchId()).orElseThrow();
        assertThat(failedBatch.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(failedBatch.getErrorMessage()).contains("Startup recovery failed", "simulated delete failure");
        assertThat(ctxEdgeRepository.findById(failing.edgeId())).isPresent();

        IngestionBatch recoveredBatch =
                ingestionBatchRepository.findById(healthy.batchId()).orElseThrow();
        assertThat(recoveredBatch.getStatus()).isEqualTo(IngestionStatus.QUEUED);
        assertThat(ctxEdgeRepository.findById(healthy.edgeId())).isEmpty();

        verify(ingestionWorker).runIngestion(healthy.batchId());
        verify(ingestionWorker, never()).runIngestion(failing.batchId());
    }

    private Orphan createOrphan(String sourcePath, String targetPath) {
        IngestionBatch batch = new IngestionBatch(repository);
        batch.setStatus(IngestionStatus.PROCESSING);
        batch = ingestionBatchRepository.saveAndFlush(batch);
        CtxNode sourceNode = ctxNodeRepository.saveAndFlush(
                new CtxNode(batch, team.getId(), repository.getName(), NodeType.FILE, sourcePath));
        CtxNode targetNode = ctxNodeRepository.saveAndFlush(
                new CtxNode(batch, team.getId(), repository.getName(), NodeType.FILE, targetPath));
        CtxEdge edge = ctxEdgeRepository.saveAndFlush(
                new CtxEdge(batch, team.getId(), sourceNode, targetNode, RelationType.CALLS));
        return new Orphan(batch.getId(), sourceNode.getId(), targetNode.getId(), edge.getId());
    }

    private record Orphan(UUID batchId, UUID sourceNodeId, UUID targetNodeId, UUID edgeId) {}
}
