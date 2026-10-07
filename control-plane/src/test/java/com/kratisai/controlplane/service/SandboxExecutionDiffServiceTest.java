package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.DiffFileDto;
import com.kratisai.controlplane.api.restdto.DiffSummaryDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionDiffSnapshot;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionDiffSnapshotRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SandboxExecutionDiffServiceTest {

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private EnvironmentRpcClient environmentRpcClient;

    @Mock
    private ExecutionDiffSnapshotRepository diffSnapshotRepository;

    @Mock
    private BlobStorageService blobStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SandboxExecutionDiffService service;

    private UUID userId;
    private UUID chatId;
    private UUID executionId;
    private UUID envId;
    private ExecutionEnvironment environment;
    private SandboxExecution execution;
    private Repository repository;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        envId = UUID.randomUUID();

        User user = new User("user@test.com", "User", "hash");
        user.setId(userId);
        Team team = new Team("Team", "desc");
        team.setId(UUID.randomUUID());
        ChatEntity chat = new ChatEntity(team, user, "Session");
        chat.setId(chatId);

        environment = new ExecutionEnvironment();
        environment.setId(envId);
        environment.setTeam(team);
        environment.setStatus(EnvironmentStatus.CONNECTED);

        repository = new Repository("my-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
        repository.setTeam(team);

        execution = new SandboxExecution();
        execution.setId(executionId);
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setRepository(repository);

        service = new SandboxExecutionDiffService(
                sandboxExecutionRepository,
                chatRepository,
                environmentRpcClient,
                diffSnapshotRepository,
                blobStorageService,
                objectMapper);

        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sandboxExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
    }

    private void stubSummary(EnvironmentConnectorResult.GitDiffSummary result) throws Exception {
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.GitDiffSummary.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(result);
    }

    private EnvironmentConnectorResult.GitDiffSummary emptySummary() {
        return new EnvironmentConnectorResult.GitDiffSummary("base", "head", 0, 0, List.of());
    }

    @Test
    void getDiffSummary_usesExecutionTargetBranch() throws Exception {
        execution.setTargetBranch("develop");
        stubSummary(emptySummary());

        service.getDiffSummary(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitDiffSummary> captor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitDiffSummary.class);
        verify(environmentRpcClient).request(eq(envId), captor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(captor.getValue().baseBranch()).isEqualTo("develop");
    }

    @Test
    void getDiffSummary_fallsBackToRepositoryBranch() throws Exception {
        repository.setBranch("trunk");
        stubSummary(emptySummary());

        service.getDiffSummary(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitDiffSummary> captor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitDiffSummary.class);
        verify(environmentRpcClient).request(eq(envId), captor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(captor.getValue().baseBranch()).isEqualTo("trunk");
    }

    @Test
    void getDiffSummary_defaultsToMainWithoutBranchConfiguration() throws Exception {
        execution.setTargetBranch(null);
        repository.setBranch(null);
        stubSummary(emptySummary());

        service.getDiffSummary(userId, chatId, executionId);

        ArgumentCaptor<EnvironmentRpcPayload.GitDiffSummary> captor =
                ArgumentCaptor.forClass(EnvironmentRpcPayload.GitDiffSummary.class);
        verify(environmentRpcClient).request(eq(envId), captor.capture(), anyLong(), eq(TimeUnit.SECONDS));
        assertThat(captor.getValue().baseBranch()).isEqualTo("main");
    }

    @Test
    void getDiffSummary_mapsResultFiles() throws Exception {
        stubSummary(new EnvironmentConnectorResult.GitDiffSummary(
                "base",
                "head",
                15,
                3,
                List.of(new EnvironmentConnectorResult.GitDiffSummaryFile(
                        "src/App.tsx", com.kratisai.controlplane.api.wsdto.GitDiffStatus.MODIFIED, 10, 2, false))));

        DiffSummaryDto result = service.getDiffSummary(userId, chatId, executionId);

        assertThat(result.baseCommit()).isEqualTo("base");
        assertThat(result.totalAdditions()).isEqualTo(15);
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().getFirst().path()).isEqualTo("src/App.tsx");
    }

    @Test
    void getDiffSummary_disconnectedEnvironment_fallsBackToSnapshot() {
        environment.setStatus(EnvironmentStatus.DISCONNECTED);
        ExecutionDiffSnapshot snapshot = new ExecutionDiffSnapshot(
                executionId,
                "commit-1",
                "commit-2",
                8,
                2,
                "[{\"path\":\"src/App.tsx\",\"status\":\"MODIFIED\",\"additions\":8,\"deletions\":2,\"isCollapsedByDefault\":false}]",
                "diffs/" + executionId + ".patch");
        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot));

        DiffSummaryDto result = service.getDiffSummary(userId, chatId, executionId);

        assertThat(result.baseCommit()).isEqualTo("commit-1");
        assertThat(result.totalAdditions()).isEqualTo(8);
        assertThat(result.totalDeletions()).isEqualTo(2);
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().getFirst().path()).isEqualTo("src/App.tsx");
    }

    @Test
    void getFileDiff_disconnectedEnvironment_extractsFileFromSnapshotBlob() {
        environment.setStatus(EnvironmentStatus.DISCONNECTED);
        String fullPatch = "diff --git a/src/App.tsx b/src/App.tsx\n"
                + "--- a/src/App.tsx\n"
                + "+++ b/src/App.tsx\n"
                + "@@ -1,2 +1,3 @@\n"
                + "-old\n"
                + "+new\n"
                + "+extra\n";
        String patchPath = "diffs/" + executionId + ".patch";
        when(blobStorageService.exists(patchPath)).thenReturn(true);
        when(blobStorageService.getObject(patchPath))
                .thenReturn(new ByteArrayInputStream(fullPatch.getBytes(StandardCharsets.UTF_8)));

        DiffFileDto fileDiff = service.getFileDiff(userId, chatId, executionId, "src/App.tsx");

        assertThat(fileDiff.path()).isEqualTo("src/App.tsx");
        assertThat(fileDiff.additions()).isEqualTo(2);
        assertThat(fileDiff.deletions()).isEqualTo(1);
        assertThat(fileDiff.patch()).contains("+new");
    }

    @Test
    void exportPatch_readsStoredBlobDirectly() {
        String fullPatch = "diff --git a/file.txt b/file.txt\n+hello";
        String patchPath = "diffs/" + executionId + ".patch";
        when(blobStorageService.exists(patchPath)).thenReturn(true);
        when(blobStorageService.getObject(patchPath))
                .thenReturn(new ByteArrayInputStream(fullPatch.getBytes(StandardCharsets.UTF_8)));

        String patch = service.exportPatch(userId, chatId, executionId);

        assertThat(patch).isEqualTo(fullPatch);
    }

    @Test
    void getReadFileSlice_noEnvironment_throwsBadRequest() {
        execution.setEnvironment(null);

        assertThatThrownBy(() -> service.getReadFileSlice(userId, chatId, executionId, "src/App.tsx", 1, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("associated environment");
    }
}
