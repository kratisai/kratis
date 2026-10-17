package com.kratisai.controlplane.e2e;

import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;

/** A non-default target: origin/HEAD is {@code main}, so only the persisted branch yields the right base. */
@SpringIntegrationTest
@SlowTest(slotGroup = "E2E_DOCKER_CONTAINER")
class SuspendResumeDevelopBranchRealTest extends AbstractSuspendResumeRealTest {

    @Override
    protected String targetBranch() {
        return GitFixtureContainer.DEVELOP_BRANCH;
    }
}
