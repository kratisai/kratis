package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.DiffFileDto;
import com.kratisai.controlplane.api.restdto.DiffSummaryDto;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.GitDiffStatus;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private SandboxExecution execution;

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

        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(envId);
        environment.setTeam(team);
        environment.setStatus(EnvironmentStatus.CONNECTED);

        Repository repository =
                new Repository("my-repo", "https://github.com/test/repo.git", "main", RepositoryType.GITHUB);
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

    private static String manifestEntry(String path, int additions, int deletions, String sha) {
        return "{\"path\":\"" + path + "\",\"status\":\"MODIFIED\",\"additions\":" + additions
                + ",\"deletions\":" + deletions + ",\"isCollapsedByDefault\":false,\"sha\":\"" + sha
                + "\",\"size\":10}";
    }

    private ExecutionDiffSnapshot snapshot(String manifestJson) {
        return new ExecutionDiffSnapshot(executionId, "commit-1", "commit-2", 8, 2, manifestJson, "digest-1");
    }

    private void storedSection(String sha, String section) {
        when(blobStorageService.getObjectBytes("diffs/" + executionId + "/" + sha))
                .thenReturn(section.getBytes(StandardCharsets.UTF_8));
    }

    private void givenSnapshot(String manifestJson) {
        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.of(snapshot(manifestJson)));
    }

    @Test
    void getDiffSummary_mapsManifestEntries() {
        givenSnapshot("[" + manifestEntry("src/App.tsx", 8, 2, "abc123") + "]");

        DiffSummaryDto result = service.getDiffSummary(userId, chatId, executionId);

        assertThat(result.baseCommit()).isEqualTo("commit-1");
        assertThat(result.headCommit()).isEqualTo("commit-2");
        assertThat(result.totalAdditions()).isEqualTo(8);
        assertThat(result.totalDeletions()).isEqualTo(2);
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().getFirst().path()).isEqualTo("src/App.tsx");
        assertThat(result.files().getFirst().status()).isEqualTo(GitDiffStatus.MODIFIED);
        assertThat(result.files().getFirst().additions()).isEqualTo(8);
    }

    @Test
    void getDiffSummary_withoutSnapshot_returnsEmptySummary() {
        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        DiffSummaryDto result = service.getDiffSummary(userId, chatId, executionId);

        assertThat(result.baseCommit()).isEmpty();
        assertThat(result.headCommit()).isEmpty();
        assertThat(result.totalAdditions()).isZero();
        assertThat(result.totalDeletions()).isZero();
        assertThat(result.files()).isEmpty();
    }

    @Test
    void getDiffSummary_corruptManifest_failsLoudly() {
        givenSnapshot("not-json");

        assertThatThrownBy(() -> service.getDiffSummary(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void getDiffSummary_entryWithoutSha_failsLoudly() {
        givenSnapshot(
                "[{\"path\":\"src/App.tsx\",\"status\":\"MODIFIED\",\"additions\":8,\"deletions\":2,\"isCollapsedByDefault\":false}]");

        assertThatThrownBy(() -> service.getDiffSummary(userId, chatId, executionId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getDiffSummary_connectedEnvironment_neverContactsConnector() {
        givenSnapshot("[]");

        service.getDiffSummary(userId, chatId, executionId);

        verifyNoInteractions(environmentRpcClient);
    }

    @Test
    void getFileDiff_returnsStoredSectionWithManifestCounts() {
        givenSnapshot("[" + manifestEntry("src/App.tsx", 2, 1, "abc123") + "]");
        storedSection("abc123", "diff --git a/src/App.tsx b/src/App.tsx\n-old\n+new\n+extra\n");

        DiffFileDto fileDiff = service.getFileDiff(userId, chatId, executionId, "src/App.tsx");

        assertThat(fileDiff.path()).isEqualTo("src/App.tsx");
        assertThat(fileDiff.additions()).isEqualTo(2);
        assertThat(fileDiff.deletions()).isEqualTo(1);
        assertThat(fileDiff.patch()).contains("+new");
    }

    @Test
    void getFileDiff_pathNotInManifest_returnsEmptyPatch() {
        givenSnapshot("[" + manifestEntry("src/Other.tsx", 1, 0, "other") + "]");

        DiffFileDto fileDiff = service.getFileDiff(userId, chatId, executionId, "src/App.tsx");

        assertThat(fileDiff.path()).isEqualTo("src/App.tsx");
        assertThat(fileDiff.patch()).isEmpty();
        verify(blobStorageService, never()).getObjectBytes(any());
    }

    @Test
    void getFileDiff_withoutSnapshot_returnsEmptyPatch() {
        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        assertThat(service.getFileDiff(userId, chatId, executionId, "src/App.tsx")
                        .patch())
                .isEmpty();
    }

    @Test
    void getFileDiff_missingSection_failsLoudly() {
        givenSnapshot("[" + manifestEntry("src/App.tsx", 1, 0, "gone") + "]");

        assertThatThrownBy(() -> service.getFileDiff(userId, chatId, executionId, "src/App.tsx"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void exportPatch_concatenatesSectionsInManifestOrder() {
        givenSnapshot("[" + manifestEntry("a.txt", 1, 0, "sha-a") + "," + manifestEntry("b.txt", 1, 0, "sha-b") + "]");
        storedSection("sha-a", "diff --git a/a.txt b/a.txt\n+a\n");
        storedSection("sha-b", "diff --git a/b.txt b/b.txt\n+b\n");

        assertThat(service.exportPatch(userId, chatId, executionId))
                .isEqualTo("diff --git a/a.txt b/a.txt\n+a\ndiff --git a/b.txt b/b.txt\n+b\n");
    }

    @Test
    void exportPatch_withoutSnapshot_returnsEmptyString() {
        when(diffSnapshotRepository.findByExecutionId(executionId)).thenReturn(Optional.empty());

        assertThat(service.exportPatch(userId, chatId, executionId)).isEmpty();
    }

    @Test
    void getReadFileSlice_requestsLiveSliceFromConnector() throws Exception {
        when(environmentRpcClient.request(
                        eq(envId), any(EnvironmentRpcPayload.ReadFileSlice.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(new EnvironmentConnectorResult.ReadFileSlice("src/App.tsx", 1, List.of("line 1")));

        var slice = service.getReadFileSlice(userId, chatId, executionId, "src/App.tsx", 1, 10);

        assertThat(slice.path()).isEqualTo("src/App.tsx");
        assertThat(slice.lines()).containsExactly("line 1");
    }

    @Test
    void getReadFileSlice_noEnvironment_throwsBadRequest() {
        execution.setEnvironment(null);

        assertThatThrownBy(() -> service.getReadFileSlice(userId, chatId, executionId, "src/App.tsx", 1, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("associated environment");
    }
}
