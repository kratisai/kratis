package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.BatchResolutionService;
import com.kratisai.controlplane.service.GraphQueryService;
import com.kratisai.controlplane.service.RemoteFileReaderService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class RepositoryToolTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private IngestionBatchRepository ingestionBatchRepository;

    @Mock
    private RemoteFileReaderService remoteFileReaderService;

    @Mock
    private BatchResolutionService batchResolutionService;

    @Mock
    private GraphQueryService graphQueryService;

    @Mock
    private CtxNodeRepository ctxNodeRepository;

    private RepositoryTool repositoryTool;

    private UUID teamId;
    private UUID batchId;
    private ToolContext toolContext;
    private IngestionBatch batch;

    @BeforeEach
    void setUp() {
        repositoryTool = new RepositoryTool(
                repositoryRepository,
                ingestionBatchRepository,
                remoteFileReaderService,
                batchResolutionService,
                graphQueryService,
                ctxNodeRepository);

        teamId = UUID.randomUUID();
        batchId = UUID.randomUUID();
        toolContext = new ToolContext(Map.of("teamId", teamId, "chatId", UUID.randomUUID()));
        batch = new IngestionBatch();
        batch.setId(batchId);
    }

    @Test
    void listRepositories_withActiveBatch_returnsIngestionStatus() {
        Team team = new Team();
        team.setId(teamId);

        Repository repo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        UUID repoId = UUID.randomUUID();
        repo.setId(repoId);

        IngestionBatch batch = new IngestionBatch();
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.setCompletedAt(Instant.parse("2026-07-25T14:30:00Z"));
        batch.setCommitHash("abc123");

        when(repositoryRepository.findByTeamId(teamId)).thenReturn(List.of(repo));
        when(ingestionBatchRepository.findByRepositoryIdAndIsActiveTrue(repoId)).thenReturn(Optional.of(batch));

        List<RepositoryTool.RepositorySummary> result = repositoryTool.listRepositories(toolContext);

        assertThat(result).hasSize(1);
        RepositoryTool.RepositorySummary summary = result.getFirst();
        assertThat(summary.name()).isEqualTo("test-repo");
        assertThat(summary.url()).isEqualTo("https://github.com/test/repo.git");
        assertThat(summary.ingestionStatus()).isEqualTo("SUCCESS");
        assertThat(summary.lastIngestedAt()).isEqualTo(Instant.parse("2026-07-25T14:30:00Z"));
        assertThat(summary.commitHash()).isEqualTo("abc123");
    }

    @Test
    void listRepositories_withoutActiveBatch_returnsNotIngested() {
        Team team = new Team();
        team.setId(teamId);

        Repository repo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        UUID repoId = UUID.randomUUID();
        repo.setId(repoId);

        when(repositoryRepository.findByTeamId(teamId)).thenReturn(List.of(repo));
        when(ingestionBatchRepository.findByRepositoryIdAndIsActiveTrue(repoId)).thenReturn(Optional.empty());

        List<RepositoryTool.RepositorySummary> result = repositoryTool.listRepositories(toolContext);

        assertThat(result).hasSize(1);
        RepositoryTool.RepositorySummary summary = result.getFirst();
        assertThat(summary.ingestionStatus()).isEqualTo("NOT_INGESTED");
        assertThat(summary.lastIngestedAt()).isNull();
        assertThat(summary.commitHash()).isNull();
    }

    @Test
    void listRepositories_withNoRepos_returnsEmptyList() {
        when(repositoryRepository.findByTeamId(teamId)).thenReturn(List.of());

        List<RepositoryTool.RepositorySummary> result = repositoryTool.listRepositories(toolContext);

        assertThat(result).isEmpty();
    }

    @Test
    void readRemoteFile_success() {
        Team team = new Team();
        team.setId(teamId);

        Repository repo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);

        when(batchResolutionService.resolveRepository(teamId, "test-repo")).thenReturn(repo);
        when(remoteFileReaderService.readFile(teamId, "test-repo", "src/Main.java", "main"))
                .thenReturn("public class Main {}");

        String result = repositoryTool.readRemoteFile("test-repo", "src/Main.java", toolContext);

        assertThat(result).isEqualTo("public class Main {}");
        verify(batchResolutionService).resolveRepository(teamId, "test-repo");
        verify(remoteFileReaderService).readFile(teamId, "test-repo", "src/Main.java", "main");
    }

    @Test
    void readRemoteFile_withNonExistentRepo_throwsException() {
        when(batchResolutionService.resolveRepository(teamId, "nonexistent"))
                .thenThrow(new IllegalArgumentException("Repository 'nonexistent' not found"));

        assertThatThrownBy(() -> repositoryTool.readRemoteFile("nonexistent", "file.txt", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getDependencies_success() {
        Team team = new Team();
        team.setId(teamId);

        Repository repo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);

        CtxNode downstreamNode = new CtxNode();
        downstreamNode.setPath("src/Service.java");
        downstreamNode.setSymbolName("doWork");

        CtxNode upstreamNode = new CtxNode();
        upstreamNode.setPath("src/Controller.java");
        upstreamNode.setSymbolName("handle");

        when(batchResolutionService.resolveRepository(teamId, "test-repo")).thenReturn(repo);
        when(graphQueryService.getDependencies(teamId, "test-repo", "src/Service.java", 2))
                .thenReturn(List.of(downstreamNode));
        when(graphQueryService.getUsages(teamId, "test-repo", "src/Service.java", 2))
                .thenReturn(List.of(upstreamNode));

        RepositoryTool.DependenciesResponse result =
                repositoryTool.getDependencies("test-repo", "src/Service.java", 2, toolContext);

        assertThat(result.downstream()).containsExactly("src/Service.java:doWork");
        assertThat(result.upstream()).containsExactly("src/Controller.java:handle");
    }

    @Test
    void getDependencies_withDepthBelowMin_clampsTo1() {
        Team team = new Team();
        team.setId(teamId);

        Repository repo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);

        when(batchResolutionService.resolveRepository(teamId, "test-repo")).thenReturn(repo);
        when(graphQueryService.getDependencies(teamId, "test-repo", "file.txt", 1))
                .thenReturn(List.of());
        when(graphQueryService.getUsages(teamId, "test-repo", "file.txt", 1)).thenReturn(List.of());

        repositoryTool.getDependencies("test-repo", "file.txt", 0, toolContext);

        verify(graphQueryService).getDependencies(teamId, "test-repo", "file.txt", 1);
        verify(graphQueryService).getUsages(teamId, "test-repo", "file.txt", 1);
    }

    @Test
    void getDependencies_withDepthAboveMax_clampsTo5() {
        Team team = new Team();
        team.setId(teamId);

        Repository repo =
                new Repository("test-repo", "https://github.com/test/repo.git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);

        when(batchResolutionService.resolveRepository(teamId, "test-repo")).thenReturn(repo);
        when(graphQueryService.getDependencies(teamId, "test-repo", "file.txt", 5))
                .thenReturn(List.of());
        when(graphQueryService.getUsages(teamId, "test-repo", "file.txt", 5)).thenReturn(List.of());

        repositoryTool.getDependencies("test-repo", "file.txt", 10, toolContext);

        verify(graphQueryService).getDependencies(teamId, "test-repo", "file.txt", 5);
        verify(graphQueryService).getUsages(teamId, "test-repo", "file.txt", 5);
    }

    @Test
    void getDependencies_withNonExistentRepo_throwsException() {
        when(batchResolutionService.resolveRepository(teamId, "nonexistent"))
                .thenThrow(new IllegalArgumentException("Repository 'nonexistent' not found"));

        assertThatThrownBy(() -> repositoryTool.getDependencies("nonexistent", "file.txt", 2, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void searchFiles_returnsDistinctPathsWithSymbols() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);

        CtxNode node1 = createNode("src/payment/PaymentService.java", "PaymentService", NodeType.CLASS);
        CtxNode node2 = createNode("src/payment/PaymentService.java", "processPayment", NodeType.METHOD);
        CtxNode node3 = createNode("src/payment/PaymentGateway.java", "PaymentGateway", NodeType.INTERFACE);
        CtxNode node4 = createNode("src/booking/Booking.java", "Booking", NodeType.CLASS);
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "payment", null))
                .thenReturn(List.of(node4, node3, node1, node2));

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "payment", null, null, toolContext);

        assertThat(result).hasSize(3);
        assertThat(result.getFirst().path()).isEqualTo("src/booking/Booking.java");
        assertThat(result.getFirst().symbols()).containsExactly("Booking");
        assertThat(result.get(1).path()).isEqualTo("src/payment/PaymentGateway.java");
        assertThat(result.get(1).symbols()).containsExactly("PaymentGateway");
        assertThat(result.get(2).path()).isEqualTo("src/payment/PaymentService.java");
        assertThat(result.get(2).symbols()).containsExactly("PaymentService", "processPayment");
        verify(ctxNodeRepository).searchByBatchIdAndQuery(batchId, "payment", null);
    }

    @Test
    void searchFiles_filtersByNodeType() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "service", NodeType.CLASS))
                .thenReturn(List.of(createNode("src/OrderService.java", "OrderService", NodeType.CLASS)));

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "service", NodeType.CLASS, null, toolContext);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().path()).isEqualTo("src/OrderService.java");
        verify(ctxNodeRepository).searchByBatchIdAndQuery(batchId, "service", NodeType.CLASS);
    }

    @Test
    void searchFiles_trimsQuery() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "payment", null))
                .thenReturn(List.of());

        repositoryTool.searchFiles("test-repo", "  payment  ", null, null, toolContext);

        verify(ctxNodeRepository).searchByBatchIdAndQuery(batchId, "payment", null);
    }

    @Test
    void searchFiles_withNullLimit_usesDefaultFifty() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        List<CtxNode> matches = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> createNode("src/file" + i + ".java", "Symbol" + i, NodeType.CLASS))
                .toList();
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "file", null)).thenReturn(matches);

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "file", null, null, toolContext);

        assertThat(result).hasSize(50);
    }

    @Test
    void searchFiles_appliesLimit() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        List<CtxNode> matches = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> createNode("src/file" + i + ".java", "Symbol" + i, NodeType.CLASS))
                .toList();
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "file", null)).thenReturn(matches);

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "file", null, 10, toolContext);

        assertThat(result).hasSize(10);
    }

    @Test
    void searchFiles_clampsLimitBelowMin_to1() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        List<CtxNode> matches = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> createNode("src/file" + i + ".java", "Symbol" + i, NodeType.CLASS))
                .toList();
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "file", null)).thenReturn(matches);

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "file", null, 0, toolContext);

        assertThat(result).hasSize(1);
    }

    @Test
    void searchFiles_clampsLimitAboveMax_to200() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        List<CtxNode> matches = java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> createNode("src/file" + i + ".java", "Symbol" + i, NodeType.CLASS))
                .toList();
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "file", null)).thenReturn(matches);

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "file", null, 500, toolContext);

        assertThat(result).hasSize(200);
    }

    @Test
    void searchFiles_withBlankQuery_throwsException() {
        assertThatThrownBy(() -> repositoryTool.searchFiles("test-repo", "   ", null, null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query must not be blank");
        verifyNoInteractions(batchResolutionService, ctxNodeRepository);
    }

    @Test
    void searchFiles_withNullQuery_throwsException() {
        assertThatThrownBy(() -> repositoryTool.searchFiles("test-repo", null, null, null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query must not be blank");
        verifyNoInteractions(batchResolutionService, ctxNodeRepository);
    }

    @Test
    void searchFiles_ignoresNullSymbolNames() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo")).thenReturn(batch);
        CtxNode fileNode = createNode("src/config/AppConfig.java", null, NodeType.FILE);
        when(ctxNodeRepository.searchByBatchIdAndQuery(batchId, "config", null)).thenReturn(List.of(fileNode));

        List<RepositoryTool.FileSearchResult> result =
                repositoryTool.searchFiles("test-repo", "config", null, null, toolContext);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().path()).isEqualTo("src/config/AppConfig.java");
        assertThat(result.getFirst().symbols()).isEmpty();
    }

    @Test
    void searchFiles_withoutActiveBatch_throwsException() {
        when(batchResolutionService.resolveActiveBatch(teamId, "test-repo"))
                .thenThrow(new IllegalArgumentException("No active ingestion batch for repository 'test-repo'"));

        assertThatThrownBy(() -> repositoryTool.searchFiles("test-repo", "payment", null, null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No active ingestion batch");
    }

    private CtxNode createNode(String path, String symbolName, NodeType nodeType) {
        CtxNode node = new CtxNode();
        node.setPath(path);
        node.setSymbolName(symbolName);
        node.setNodeType(nodeType);
        return node;
    }
}
