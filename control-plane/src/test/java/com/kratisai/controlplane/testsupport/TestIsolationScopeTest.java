package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestIsolationScopeTest {

    @Test
    void derivesDistinctStableScopesForParallelWorktreesAndDockerContainers() {
        String hostA = TestIsolationScope.resolveScopeId(null, "/repo-a/control-plane", false, "devbox");
        String hostB = TestIsolationScope.resolveScopeId(null, "/repo-b/control-plane", false, "devbox");
        assertThat(hostA)
                .hasSize(8)
                .isEqualTo(TestIsolationScope.resolveScopeId(null, "/repo-a/control-plane", false, "devbox"));
        assertThat(hostA).isNotEqualTo(hostB);

        // Two Docker containers mounting the same /workspace path must still diverge by container hostname
        String container1 = TestIsolationScope.resolveScopeId(null, "/workspace/control-plane", true, "c1a2b3c4");
        String container2 = TestIsolationScope.resolveScopeId(null, "/workspace/control-plane", true, "d5e6f7a8");
        assertThat(container1).hasSize(8).isNotEqualTo(container2);
    }

    @Test
    void sanitizesExplicitScopeOverrideAndScopesAllResourceNames() {
        String custom = TestIsolationScope.resolveScopeId("Feat/My_Branch#42", "/workspace", true, "c1");
        assertThat(custom).isEqualTo("feat-my-branch-42");

        assertThat(TestIsolationScope.networkName()).startsWith("kratis-test-net-");
        assertThat(TestIsolationScope.instanceId()).startsWith("kratis-test-");
        assertThat(TestIsolationScope.runnerImageTag()).startsWith("kratis-runner-base:");
        assertThat(TestIsolationScope.e2eImageTag()).startsWith("kratis-e2e-test:");
        assertThat(TestIsolationScope.gitFixtureImageTag()).startsWith("kratis-git-fixture:");
        assertThat(TestIsolationScope.cbmCacheDir()).contains("kratis-cbm-cache-" + TestIsolationScope.scopeId());
        assertThat(TestIsolationScope.blobDir()).contains("kratis-test-blobs-" + TestIsolationScope.scopeId());
        assertThat(TestIsolationScope.workspaceScope()).endsWith("@" + TestIsolationScope.scopeId());
    }
}
