package com.kratisai.controlplane.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SpringIntegrationTest
@SlowTest(slotGroup = "E2E_DOCKER_CONTAINER")
class SuspendResumeMainBranchRealTest extends AbstractSuspendResumeRealTest {

    @Override
    protected String targetBranch() {
        return "main";
    }

    /**
     * Sandboxes suspended before the base branch was persisted have no {@code diff_base_branch}; git's
     * own {@code origin/HEAD} must still keep the diff local to the sandbox.
     */
    @Test
    void snapshotWithoutPersistedBaseBranchFallsBackToOriginHead() throws Exception {
        runFirstTurnAndSleep();
        UUID envId = spawnedEnvironmentId();
        String snapshotImage = "kratis-snapshot-" + envId + ":latest";
        String scratch = "kratis-legacy-" + envId;
        dockerOutput(
                "docker",
                "run",
                "--name",
                scratch,
                "--entrypoint",
                "sh",
                snapshotImage,
                "-c",
                "rm -f /kratis/diff_base_branch");
        try {
            dockerOutput("docker", "commit", scratch, snapshotImage);
        } finally {
            dockerOutput("docker", "rm", "-f", scratch);
        }

        int acpInitialized = acpInitializedCount();
        resumeViaRest(envId);
        awaitResumed(envId, acpInitialized);
        assertThat(createDockerVerifier().fileExists("/kratis/diff_base_branch"))
                .as("the legacy snapshot carries no persisted branch")
                .isFalse();

        assertDiffIsLocalOnly(latestExecution(envId).getId(), "legacy_added.txt");
    }
}
