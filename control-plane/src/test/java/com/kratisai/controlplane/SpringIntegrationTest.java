package com.kratisai.controlplane;

import com.kratisai.controlplane.planningagent.TestAgentConfig;
import com.kratisai.controlplane.service.ProcessExecutorTestConfig;
import java.lang.annotation.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Integration tests that use the full Spring Boot context backed by a Postgres
 * TestContainer.
 * <p>
 * {@link ThreadBoundContextCustomizerFactory} assigns each test class a stable
 * "slot" and {@link ContextSlotLockExtension} prevents two classes in the same
 * slot running concurrently
 * <p>
 * Because slots never share schema state concurrently, no global
 * {@code @ResourceLock} is required.
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ResourceLock(providers = DynamicContextSlotLockProvider.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = {
            DrainExecutorsTestExecutionListener.class,
            ResetChatModelTestExecutionListener.class,
            SlowTestTimingListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Import({
    TestDataFactory.class,
    SandboxExecutionScenarioFactory.class,
    FakeChatModelConfig.class,
    ModelDiscoveryTestConfig.class,
    LiteLLMTestConfig.class,
    ProcessExecutorTestConfig.class,
    FakeGitHubApiClientConfig.class,
    FakeGitLabApiClientConfig.class,
    FakeBitbucketApiClientConfig.class,
    FakeAzureDevOpsApiClientConfig.class,
    DatabaseCleaner.class,
    TestAgentConfig.class,
    TestSecurityConfig.class
})
public @interface SpringIntegrationTest {}
