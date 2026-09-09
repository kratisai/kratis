package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.*;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
public class IngestionQueueManagerIntegrationTest {

    @Autowired
    private IngestionQueueManager ingestionQueueManager;

    @Autowired
    private IngestionBatchRepository ingestionBatchRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private CtxNodeRepository ctxNodeRepository;

    private Path tempRepoDir;

    @BeforeEach
    void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("test-local-repo");
        runGitCommand(tempRepoDir, "init");
        runGitCommand(tempRepoDir, "branch", "-M", "main");
        runGitCommand(tempRepoDir, "config", "user.email", "test@test.com");
        runGitCommand(tempRepoDir, "config", "user.name", "Test User");
        Files.writeString(tempRepoDir.resolve("README.md"), "test content");
        runGitCommand(tempRepoDir, "add", "README.md");
        runGitCommand(tempRepoDir, "commit", "-m", "Initial commit");
    }

    private void runGitCommand(Path dir, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git");
        for (String arg : args) {
            pb.command().add(arg);
        }
        pb.directory(dir.toFile());
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Git command failed: " + String.join(" ", args));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempRepoDir != null) {
            try (var stream = Files.walk(tempRepoDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception e) {
                        // ignore
                    }
                });
            }
        }
    }

    @Test
    void testQueueManagerRecoveryBehavior() {
        // 1. Setup: Create a team and repository
        Team team = new Team("Test Team", "test-team");
        team = teamRepository.save(team);

        Repository repo = new Repository();
        repo.setName("test-repo");
        repo.setUrl(tempRepoDir.toUri().toString());
        repo.setBranch("main");
        repo.setTeam(team);
        repo = repositoryRepository.save(repo);

        // Create Batch A (PROCESSING) with partial data
        IngestionBatch batchA = new IngestionBatch(repo);
        batchA.setStatus(IngestionStatus.PROCESSING);
        IngestionBatch savedBatchA = ingestionBatchRepository.saveAndFlush(batchA);
        CtxNode nodeA = new CtxNode(savedBatchA, team.getId(), repo.getName(), NodeType.FILE, "src/A.java");
        ctxNodeRepository.saveAndFlush(nodeA);
        UUID nodeAId = nodeA.getId();

        // Create Batch B (FAILED) with partial data
        IngestionBatch batchB = new IngestionBatch(repo);
        batchB.setStatus(IngestionStatus.FAILED);
        batchB.setErrorMessage("Previous failure");
        IngestionBatch savedBatchB = ingestionBatchRepository.saveAndFlush(batchB);
        CtxNode nodeB = new CtxNode(savedBatchB, team.getId(), repo.getName(), NodeType.FILE, "src/B.java");
        ctxNodeRepository.saveAndFlush(nodeB);
        UUID nodeBId = nodeB.getId();

        // 2. Action: Invoke queue manager recovery
        ingestionQueueManager.recoverAndResubmit();

        // 3. Assertion: Wait for the async worker to finish processing Batch A, and assert its initial partial data is
        // DELETED
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    IngestionBatch updatedBatchA = ingestionBatchRepository
                            .findById(savedBatchA.getId())
                            .orElseThrow();
                    assertThat(updatedBatchA.getStatus()).isNotEqualTo(IngestionStatus.QUEUED);
                    assertThat(updatedBatchA.getStatus()).isNotEqualTo(IngestionStatus.PROCESSING);
                    assertThat(ctxNodeRepository.findById(nodeAId)).isEmpty();
                });

        // 4. Assertion: Batch B remains FAILED, and its partial data is RETAINED with the SAME ID
        IngestionBatch updatedBatchB =
                ingestionBatchRepository.findById(savedBatchB.getId()).orElseThrow();
        assertThat(updatedBatchB.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(ctxNodeRepository.findById(nodeBId)).isPresent();
        assertThat(ctxNodeRepository.findById(nodeBId).get().getPath()).isEqualTo("src/B.java");
    }
}
