package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.*;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class SandboxExecutionMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Test
    void testSandboxExecutionWithModelProviderAndModelName() {
        // Create test context with team, user, repository, and model provider
        TestDataFactory.TestContext context = testDataFactory.createDefaultContext();

        // Create execution environment
        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setTeam(context.team());
        env.setName("Test Sandbox");
        env.setType(ExecutionEnvironmentType.SANDBOX);
        env.setContainerId("test-container");
        env.setStatus(EnvironmentStatus.DISCONNECTED);
        executionEnvironmentRepository.save(env);

        // Create chat
        ChatEntity chat = new ChatEntity(context.team(), context.user(), "Test Chat");
        chatRepository.save(chat);

        entityManager.flush();

        // Create sandbox execution with model provider and model name
        SandboxExecution execution = new SandboxExecution();
        execution.setEnvironment(env);
        execution.setChat(chat);
        execution.setHarness(AgentHarness.OPENCODE);
        execution.setTaskPrompt("Test task");
        execution.setModelProvider(context.provider());
        execution.setModelName("gpt-4o");
        sandboxExecutionRepository.save(execution);

        entityManager.flush();

        // Verify the execution was saved with model provider and model name
        assertThat(execution.getId()).isNotNull();
        SandboxExecution found =
                sandboxExecutionRepository.findById(execution.getId()).orElseThrow();
        assertThat(found.getModelProvider()).isNotNull();
        assertThat(found.getModelProvider().getId())
                .isEqualTo(context.provider().getId());
        assertThat(found.getModelName()).isEqualTo("gpt-4o");
    }

    @Test
    void testSandboxExecutionModelProviderGetterSetter() {
        // Unit-test style verification of getter/setter without DB
        SandboxExecution execution = new SandboxExecution();

        assertThat(execution.getModelProvider()).isNull();
        assertThat(execution.getModelName()).isNull();

        ModelProvider provider = new ModelProvider("test", ProviderType.OPENAI, "key", null);
        provider.setId(UUID.randomUUID());

        execution.setModelProvider(provider);
        execution.setModelName("claude-3-opus");

        assertThat(execution.getModelProvider()).isEqualTo(provider);
        assertThat(execution.getModelProvider().getId()).isEqualTo(provider.getId());
        assertThat(execution.getModelName()).isEqualTo("claude-3-opus");
    }
}
